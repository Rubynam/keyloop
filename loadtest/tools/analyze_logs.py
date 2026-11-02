"""Reads everything one ramp run left behind and lists potential bugs.

Inputs (written by loadtest/run-ramp.sh into one run folder; <p> = profile, ramp, book-ramp or book-burst):
    logs/*.log                     Log4j2 files of server-api replicas and scheduler-batch
    <p>_stats.csv, <p>_stats_history.csv, <p>_failures.csv     Locust
    verdict-<p>.json               Locust budgets (thresholds.json) + booking status mix
    ramp_simulator.json            booking simulator summary (profile ramp only)
    docker_stats.csv               CPU / memory samples per container
    invariants.txt, relay_lag.txt, redis_info.txt, pg_stats.txt   post-run probes
    burst_check.txt                bookings per hot slot vs capacity (profile book-burst only)

    python3 loadtest/tools/analyze_logs.py loadtest/reports/ramp-<ts>
    python3 loadtest/tools/analyze_logs.py loadtest/reports/book-ramp-<ts> book-ramp
    → <run>/analysis.md and <run>/analysis.json

    log line  2026-11-01 11:23:00.034  INFO [cid] [thread] c.k.s.b.s.JobScheduler : message
              └ ts ──────────────────┘ └lvl┘ └cid┘ └thread┘ └ logger ──────────────┘ └ msg
"""
import csv
import json
import os
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

LINE = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\.\d{3}\s+(TRACE|DEBUG|INFO|WARN|ERROR|FATAL) "
                  r"\[([^\]]*)\] \[([^\]]*)\] (\S+) : (.*)$")
ASPECT_ERROR = re.compile(r"^\[(\w+)\]\[(\w+)\]\[[^\]]*\]\[.*\]\[(\w+(?:\.\w+)*): ")
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\b[0-9a-f]{32}\b")
NUM = re.compile(r"\b\d+(\.\d+)?\b")

# Client errors the API answers on purpose (4xx). Logged at ERROR they are noise, not bugs.
CLIENT_ERRORS = {"NotFoundException", "InvalidRangeException", "MethodArgumentTypeMismatchException",
                 "MissingServletRequestParameterException", "MethodArgumentNotValidException",
                 # Booking (Spec07): 400 / 409 / 422 by ApiExceptionHandler
                 "VehicleOwnerMismatchException", "VehicleBusyException", "IdempotencyKeyMismatchException",
                 "NotServiceableException", "OutsideBusinessHoursException", "OffSlotGridException",
                 "BookingConflictException"}
TARGET_RPS = int(os.getenv("RAMP_MAX_RPS", "100"))

# (id, what it means, regex on message + stack text)
DETECTORS = [
    ("unhandled-500", "ApiExceptionHandler answered 500 (bug or unmapped exception)", r"Unexpected error"),
    ("db-error-503", "Database error answered 503", r"Database error"),
    ("pool-exhausted", "HikariCP pool exhausted: requests waited for a DB connection",
     r"Connection is not available|request timed out after|HikariPool.*(?:timeout|exhausted)"),
    ("pool-leak", "HikariCP connection leak detected", r"Connection leak detection"),
    ("cache-fail-open", "Redis error: availability read fell back to PostgreSQL", r"Availability cache unavailable"),
    ("redis-timeout", "Redis command timed out", r"RedisCommandTimeoutException|Command timed out"),
    ("cache-wait-interrupted", "Request interrupted while waiting for a cache rebuild",
     r"Interrupted while waiting for the availability cache"),
    ("relay-retry", "Relay could not bump a version key and scheduled a retry", r"Cache invalidation try \d+ failed"),
    ("relay-failed", "Outbox event FAILED after all retries (stale cache until TTL)", r"Cache invalidation failed after"),
    ("relay-invalid", "Outbox event with an invalid payload", r"has an invalid payload"),
    ("lock-timeout", "Lock / statement timeout in PostgreSQL", r"lock timeout|statement timeout|canceling statement"),
    ("deadlock", "PostgreSQL deadlock", r"deadlock detected"),
    ("oom", "Out of memory", r"OutOfMemoryError"),
    ("broken-pipe", "Client closed the connection before the answer (timeout upstream)",
     r"Broken pipe|ClientAbortException|Connection reset by peer"),
    ("thread-starvation", "Thread pool starvation", r"Thread starvation|clock leap|pool is full|RejectedExecution"),
    ("heartbeat-failed", "Heartbeat job did not complete", r"heartbeatJob (?:failed|finished with status (?!COMPLETED))"),
]


PROBES = ("cache_freshness.txt", "relay_lag.txt", "invariants.txt", "burst_check.txt", "redis_info.txt", "pg_stats.txt")


def norm(msg):
    return NUM.sub("<n>", UUID.sub("<id>", msg))[:220]


def service_of(path):
    return re.sub(r"-[0-9a-f]{12}$|-[\w.]+local$", "", path.stem)


def read_logs(folder, since=""):
    """Entries: (file, ts, level, cid, thread, logger, msg, extra lines), from `since` on."""
    entries = []
    for path in sorted(folder.glob("*.log")):
        last = None
        for raw in path.read_text(errors="replace").splitlines():
            m = LINE.match(raw)
            if m and m.group(1) < since:
                last = None                      # before the run: drop it and its stack trace
            elif m:
                last = [path.stem, *m.groups(), []]
                entries.append(last)
            elif last is not None:
                last[7].append(raw)          # stack trace / continuation
    return entries


def analyze_logs(entries):
    by_file = defaultdict(Counter)
    per_second = defaultdict(Counter)
    warn_err = defaultdict(Counter)
    client_errors = defaultdict(Counter)
    exceptions = Counter()
    detectors = Counter()
    examples = {}
    aspect_lines = 0
    for f, ts, level, cid, thread, logger, msg, extra in entries:
        by_file[f][level] += 1
        per_second[f][ts] += 1
        if logger.endswith("MethodLoggingAspect"):
            aspect_lines += 1
        text = msg + "\n" + "\n".join(extra[:40])
        for line in extra:
            m = re.match(r"^\s*(?:Caused by: )?([\w.$]+(?:Exception|Error))\b", line)
            if m:
                exceptions[m.group(1).rsplit(".", 1)[-1]] += 1
        a = ASPECT_ERROR.match(msg) if level in ("WARN", "ERROR") else None
        if a and a.group(3) in CLIENT_ERRORS:
            # Expected 4xx: counted apart from real WARN/ERROR; only a problem when logged at ERROR
            client_errors[level][f"{a.group(1)}.{a.group(2)} → {a.group(3)}"] += 1
        elif level in ("WARN", "ERROR", "FATAL"):
            warn_err[f"{level} {logger}"][norm(msg)] += 1
        for did, _, rx in DETECTORS:
            if re.search(rx, text):
                detectors[did] += 1
                examples.setdefault(did, f"{f} {ts} {level} [{cid}] {msg[:300]}")
    peak = {f: max(c.values()) for f, c in per_second.items() if c}
    return {"byFile": {f: dict(c) for f, c in by_file.items()}, "peakLinesPerSecond": peak,
            "aspectLines": aspect_lines, "totalLines": len(entries),
            "warnErrorTop": {k: c.most_common(8) for k, c in warn_err.items()},
            "clientErrorsLoggedAsError": dict(client_errors["ERROR"]),
            "clientErrorsAtWarn": dict(client_errors["WARN"]), "exceptions": dict(exceptions.most_common(15)),
            "detectors": dict(detectors), "examples": examples}


def read_csv(path):
    return list(csv.DictReader(path.open())) if path.exists() else []


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def analyze_locust(run, verdict, profile):
    stats = {r["Name"]: r for r in read_csv(run / f"{profile}_stats.csv")}
    history = [r for r in read_csv(run / f"{profile}_stats_history.csv") if r["Name"] == "Aggregated"]
    # Latency per load band: did p95 grow with RPS? (the knee)
    bands = defaultdict(list)
    for r in history:
        users, p95, rps = num(r["User Count"]), num(r["95%"]), num(r["Requests/s"])
        if users and p95 is not None and rps is not None:
            bands[min(100, int(users // 20) * 20)].append((rps, p95, num(r["Failures/s"]) or 0))
    curve = {b: {"rps": round(sum(x[0] for x in v) / len(v), 1), "p95MsMax": max(x[1] for x in v),
                 "failuresPerS": round(max(x[2] for x in v), 2)} for b, v in sorted(bands.items())}
    peak_rps = max((num(r["Requests/s"]) or 0 for r in history), default=0)
    failures = read_csv(run / f"{profile}_failures.csv")
    endpoints = {n: {"requests": int(r["Request Count"]), "failures": int(r["Failure Count"]),
                     "p50": num(r["50%"]), "p95": num(r["95%"]), "p99": num(r["99%"]), "max": num(r["Max Response Time"]),
                     "rps": num(r["Requests/s"])} for n, r in stats.items()}
    return {"endpoints": endpoints, "curve": curve, "peakRps": peak_rps,
            "failures": [{"name": f["Name"], "error": f["Error"][:300], "n": int(f["Occurrences"])} for f in failures],
            "verdict": verdict}


def analyze_docker(run):
    peak = defaultdict(lambda: {"cpu": 0.0, "memMiB": 0.0})
    for r in read_csv(run / "docker_stats.csv"):
        name = re.sub(r"^keyloop-scheduler-", "", r["name"])
        cpu = num(r["cpu"].rstrip("%")) or 0
        mem = r["mem"].split("/")[0].strip()
        mib = num(re.sub(r"[A-Za-z]+$", "", mem)) or 0
        mib *= {"GiB": 1024, "MiB": 1, "KiB": 1 / 1024, "B": 1 / 1048576}.get(re.sub(r"^[\d.]+", "", mem), 1)
        peak[name]["cpu"] = max(peak[name]["cpu"], cpu)
        peak[name]["memMiB"] = round(max(peak[name]["memMiB"], mib), 1)
    return dict(peak)


def text(run, name):
    p = run / name
    return p.read_text() if p.exists() else ""


def findings(logs, locust, docker, sim, run, profile):
    """Turns the numbers into a ranked list of potential problems."""
    out = []

    def add(sev, area, what, evidence):
        out.append({"severity": sev, "area": area, "finding": what, "evidence": evidence})

    d = logs["detectors"]
    for did, meaning, _ in DETECTORS:
        if d.get(did):
            sev = "high" if did in ("unhandled-500", "pool-exhausted", "oom", "deadlock", "relay-failed", "pool-leak") \
                else "medium" if did in ("db-error-503", "cache-fail-open", "redis-timeout", "lock-timeout",
                                         "relay-retry", "heartbeat-failed", "thread-starvation") else "low"
            add(sev, "logs", f"{meaning} ({d[did]}×)", logs["examples"][did])

    fivexx = sum(f["n"] for f in locust["failures"] if re.search(r"status 5\d\d", f["error"]))
    if fivexx:
        add("high", "http", f"{fivexx} responses with status 5xx", "; ".join(
            f"{f['name']}: {f['error'][:120]}" for f in locust["failures"] if re.search(r"status 5\d\d", f["error"]))[:600])
    other = [f for f in locust["failures"] if not re.search(r"status 5\d\d", f["error"])]
    if other:
        add("medium", "http", f"{sum(f['n'] for f in other)} responses did not match the expected output",
            "; ".join(f"{f['name']} ×{f['n']}: {f['error'][:140]}" for f in other[:5]))
    for err in (locust["verdict"] or {}).get("errors", []):
        add("medium", "budget", f"Budget missed: {err}", f"thresholds.json profile '{profile}'")
    curve = locust["curve"]
    if curve:
        low, high = curve[min(curve)], curve[max(curve)]
        if low["p95MsMax"] and high["p95MsMax"] > 3 * max(low["p95MsMax"], 10):
            add("medium", "latency", f"p95 grows with load: {low['p95MsMax']:.0f} ms at ≤{min(curve)+19} users → "
                f"{high['p95MsMax']:.0f} ms at {max(curve)} users", json.dumps(curve))
    if locust["peakRps"] and locust["peakRps"] < 0.9 * TARGET_RPS:
        add("medium", "throughput", f"Peak only {locust['peakRps']:.1f} RPS, target {TARGET_RPS}",
            f"{profile}_stats_history.csv")

    for line in text(run, "cache_freshness.txt").splitlines():
        if line.startswith("MISMATCH"):
            add("high", "cache", "Stale cache after the relay drained: cached slots differ from PostgreSQL", line)

    if logs["clientErrorsLoggedAsError"]:
        n = sum(logs["clientErrorsLoggedAsError"].values())
        add("low", "logging", f"{n} expected client errors (4xx) logged at ERROR by MethodLoggingAspect: alert noise, "
            "real errors drown", json.dumps(logs["clientErrorsLoggedAsError"]))
    total_req = sum(e["requests"] for n, e in locust["endpoints"].items() if n != "Aggregated")
    if total_req and logs["aspectLines"] / total_req > 1.5:
        add("low", "logging", f"{logs['aspectLines'] / total_req:.1f} aspect log lines per HTTP request "
            f"(peak {max(logs['peakLinesPerSecond'].values())} lines/s in one file): disk and I/O cost at scale",
            json.dumps(logs["peakLinesPerSecond"]))

    for name, p in docker.items():
        if p["cpu"] > 180:
            add("medium", "resources", f"{name} CPU peaked at {p['cpu']:.0f}%", "docker_stats.csv")

    inv = text(run, "invariants.txt")
    bad = [b for b in re.split(r"\n(?=INV-)", inv) if re.search(r"\(\s*[1-9]\d* rows?\)", b)]
    for block in bad:
        add("high", "data", f"Invariant broken: {block.splitlines()[0]}", block[:500])
    over = re.search(r"BB-INV-01.*?\((\d+) rows?\)", text(run, "burst_check.txt"), re.S)
    if over and int(over.group(1)):
        add("high", "data", f"{over.group(1)} hot slots hold more bookings than their capacity", "burst_check.txt BB-INV-01")
    lag = text(run, "relay_lag.txt")
    m = re.search(r"^(\d+)\|(\d+)\|(\d+)\|([\d.]+)\|([\d.]+)\|([\d.]+)$", lag, re.M)
    if m:
        total, pending, failed, p50, p95, mx = m.groups()
        if int(pending) or int(failed):
            add("high", "relay", f"Outbox not drained after the run: {pending} pending, {failed} failed", lag.strip())
        if float(p95) > 2000:
            add("medium", "relay", f"Cache invalidation lag p95 {float(p95):.0f} ms (created → processed)", lag.strip())
    if sim:
        errs = sum(v for k, v in sim.get("counts", {}).items() if k.endswith(":error"))
        if errs:
            add("high", "simulator", f"{errs} booking writes failed with an unexpected SQL error", json.dumps(sim))
        if (sim.get("latencyMs") or {}).get("p95") and sim["latencyMs"]["p95"] > 200:
            add("medium", "db", f"Booking write p95 {sim['latencyMs']['p95']} ms under read load", json.dumps(sim["latencyMs"]))
    order = {"high": 0, "medium": 1, "low": 2}
    return sorted(out, key=lambda f: order[f["severity"]])


def markdown(result):
    L = [f"# {result['profile']} run analysis — {result['run']}", ""]
    L += ["## Potential issues", "", "| # | Severity | Area | Finding | Evidence |", "|---|---|---|---|---|"]
    for i, f in enumerate(result["findings"], 1):
        ev = f["evidence"].replace("|", "\\|").replace("\n", " ")[:400]
        L.append(f"| {i} | {f['severity']} | {f['area']} | {f['finding']} | `{ev}` |")
    if not result["findings"]:
        L.append("| – | – | – | No potential issue detected | – |")
    loc = result["locust"]
    L += ["", f"## HTTP (Locust) — peak {loc['peakRps']:.1f} RPS", "",
          "| Endpoint | Requests | Failures | p50 | p95 | p99 | max |", "|---|---|---|---|---|---|---|"]
    for n, e in loc["endpoints"].items():
        L.append(f"| {n} | {e['requests']} | {e['failures']} | {e['p50']} | {e['p95']} | {e['p99']} | {e['max']} |")
    unit = "1 user = 1 wave lane" if result["profile"] == "book-burst" else "1 user = 1 RPS"
    L += ["", f"### Latency vs load (Aggregated, per 20-user band; {unit})", "",
          "| Users | Avg RPS | Max p95 ms | Max failures/s |", "|---|---|---|---|"]
    for b, c in loc["curve"].items():
        L.append(f"| {b}–{min(b + 19, 100)} | {c['rps']} | {c['p95MsMax']} | {c['failuresPerS']} |")
    lg = result["logs"]
    L += ["", f"## Logs — {lg['totalLines']} lines", "", "| File | Levels | Peak lines/s |", "|---|---|---|"]
    for f, c in lg["byFile"].items():
        L.append(f"| {f} | {', '.join(f'{k} {v}' for k, v in sorted(c.items()))} | {lg['peakLinesPerSecond'].get(f)} |")
    L += ["", "### WARN / ERROR (normalized, client 4xx excluded)", ""]
    for k, top in lg["warnErrorTop"].items():
        for msg, n in top:
            L.append(f"- `{k}` ×{n}: `{msg}`")
    if not lg["warnErrorTop"]:
        L.append("- none")
    L += ["", "### Expected 4xx (client errors) by log level", ""]
    L += [f"- ERROR {k}: {v}" for k, v in lg["clientErrorsLoggedAsError"].items()]
    L += [f"- WARN {k}: {v}" for k, v in lg.get("clientErrorsAtWarn", {}).items()]
    if not lg["clientErrorsLoggedAsError"] and not lg.get("clientErrorsAtWarn"):
        L.append("- none")
    L += ["", "### Exceptions in stack traces", ""]
    L += [f"- {k}: {v}" for k, v in lg["exceptions"].items()] or ["- none"]
    L += ["", "## Containers (peak)", "", "| Container | CPU % | Memory MiB |", "|---|---|---|"]
    for n, p in sorted(result["docker"].items()):
        L.append(f"| {n} | {p['cpu']:.0f} | {p['memMiB']} |")
    outcomes = (loc["verdict"] or {}).get("aggregates", {})
    if outcomes:
        L += ["", "## Booking outcomes (status mix per case)", "", "| Case | Counts | OK |", "|---|---|---|"]
        for cid, r in outcomes.items():
            L.append(f"| {cid} | {', '.join(f'{s}: {n}' for s, n in sorted(r['counts'].items()))} | {r['ok']} |")
    waves = (loc["verdict"] or {}).get("waves", {})
    if waves:
        L += ["", "## Waves (same slot, same instant)", "",
              "| Case | Waves | Exact | Over capacity | 201s per wave → waves | Send skew p50 / max ms | Wave p50 / p95 ms |",
              "|---|---|---|---|---|---|---|"]
        for cid, w in waves.items():
            L.append(f"| {cid} | {w['waves']} | {w['exact']} | {w['over']} | "
                     f"{', '.join(f'{k}→{v}' for k, v in w['201PerWave'].items())} | "
                     f"{w['skewMs']['p50']} / {w['skewMs']['max']} | {w['waveMs']['p50']} / {w['waveMs']['p95']} |")
    if result["simulator"]:
        L += ["", "## Booking simulator", "", "```json", json.dumps(result["simulator"], indent=2), "```"]
    for name in PROBES:
        if name == "burst_check.txt" and not result["probes"].get(name):
            continue                            # book-burst only
        L += ["", f"## {name}", "", "```", result["probes"].get(name, "").strip() or "(missing)", "```"]
    return "\n".join(L) + "\n"


def main(run, profile="ramp"):
    run = Path(run)
    verdict = json.loads(text(run, f"verdict-{profile}.json") or "null")
    sim = json.loads(text(run, "ramp_simulator.json") or "null")
    logs = analyze_logs(read_logs(run / "logs", text(run, "start.txt").strip()))
    locust = analyze_locust(run, verdict, profile)
    docker = analyze_docker(run)
    result = {"run": run.name, "profile": profile, "logs": logs, "locust": locust, "docker": docker, "simulator": sim,
              "probes": {n: text(run, n) for n in PROBES}}
    result["findings"] = findings(logs, locust, docker, sim, run, profile)
    (run / "analysis.json").write_text(json.dumps(result, indent=2, ensure_ascii=False, default=str) + "\n")
    (run / "analysis.md").write_text(markdown(result))
    print(markdown(result))
    return 1 if any(f["severity"] == "high" for f in result["findings"]) else 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:3]))
