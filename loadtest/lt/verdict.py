"""End-of-run verdict (Spec04 §6): latency / error budgets and per-case aggregates.

Written to reports/verdict-<profile>.json; a failed budget sets the process exit code to 1,
so `docker compose run` (or CI) fails when the system misses its targets.
"""
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]
REPORTS = BASE / "reports"

# Cases with an "aggregate" expectation (standalone runs only: race, abuse):
# case id -> list of (status, sameValue)
AGGREGATES = defaultdict(list)


def _budget(entry, rule):
    out = {"requests": entry.num_requests, "failRatio": round(entry.fail_ratio, 4)}
    errs = []
    if entry.num_requests < rule.get("minRequests", 1):
        errs.append(f"only {entry.num_requests} requests")
    for key, pct in (("p50", 0.50), ("p95", 0.95), ("p99", 0.99)):
        if key in rule:
            out[key] = entry.get_response_time_percentile(pct)
            if out[key] > rule[key]:
                errs.append(f"{key} {out[key]} ms > {rule[key]} ms")
    if "maxFailRatio" in rule and entry.fail_ratio > rule["maxFailRatio"]:
        errs.append(f"fail ratio {entry.fail_ratio:.2%} > {rule['maxFailRatio']:.2%}")
    return out, errs


def aggregate_verdict(cases):
    result = {}
    for case in cases:
        agg, seen = case["aggregate"], AGGREGATES.get(case["id"], [])
        counts = Counter(s for s, _ in seen)
        errs = [f"status {s} not allowed" for s in counts if s not in agg["allowed"]]
        for status, n in agg.get("exactly", {}).items():
            if counts.get(int(status), 0) != n:
                errs.append(f"{counts.get(int(status), 0)} x {status}, expected exactly {n}")
        for status, n in agg.get("atLeast", {}).items():
            if counts.get(int(status), 0) < n:
                errs.append(f"{counts.get(int(status), 0)} x {status}, expected at least {n}")
        if agg.get("sameValue"):
            values = {v for s, v in seen if s in agg["allowed"]}
            if len(values) > 1:
                errs.append(f"{len(values)} different '{agg['sameValue']}' values, expected 1")
        result[case["id"]] = {"goal": case["goal"], "counts": dict(counts), "ok": not errs, "errors": errs}
    return result


def evaluate(environment, profile, thresholds, aggregate_cases=()):
    stats = environment.stats
    report = {"profile": profile, "endpoints": {}, "errors": []}

    rules = {**thresholds.get("default", {}), **thresholds.get("profiles", {}).get(profile, {})}
    for name, rule in rules.get("endpoints", {}).items():
        entries = [e for (n, _), e in stats.entries.items() if n == name]
        if not entries:
            report["errors"].append(f"{name}: no requests")
            continue
        for e in entries:
            out, errs = _budget(e, rule)
            report["endpoints"][f"{e.method} {name}"] = {**out, "ok": not errs, "errors": errs}
            report["errors"] += [f"{name}: {x}" for x in errs]

    glob = rules.get("global", {})
    total = stats.total
    report["total"] = {"requests": total.num_requests, "failRatio": round(total.fail_ratio, 4)}
    if "maxFailRatio" in glob and total.fail_ratio > glob["maxFailRatio"]:
        report["errors"].append(f"total fail ratio {total.fail_ratio:.2%} > {glob['maxFailRatio']:.2%}")
    # A 5xx is never an expected output (nginx 503 included)
    n5xx = sum(e.occurrences for e in stats.errors.values() if re.search(r"status 5\d\d", str(e.error)))
    report["total"]["5xx"] = n5xx
    if n5xx > glob.get("max5xx", 0):
        report["errors"].append(f"{n5xx} responses with 5xx")

    if aggregate_cases:
        report["aggregates"] = aggregate_verdict(aggregate_cases)
        report["errors"] += [f"{cid}: {x}" for cid, r in report["aggregates"].items() for x in r["errors"]]

    report["ok"] = not report["errors"]
    REPORTS.mkdir(exist_ok=True)
    (REPORTS / f"verdict-{profile}.json").write_text(json.dumps(report, indent=2, ensure_ascii=False))
    return report
