"""Loads the JSON test data and renders requests (Spec06 §5).

Input and expected output live in two files per suite, joined by case id:

    input/<suite>.json      what is sent:  goal, verifies, weight, bind, request | steps[]
    expected/<suite>.json   what must come back: expect | steps[].expect, aggregate
                            steps[].expect.continueOn: statuses after which the case goes on
                            (any other allowed status passes, but ends the case)

Pipeline per case execution:
    1. load_suite()  read input + expected, join by case id (and step id), fail on any gap
    2. bind()        make the case's values (uuid, random car, slot time, ...)
    3. render()      replace {{tokens}} in the request AND in the expectation
    4. send          (locustfile) → checks.check(expectation, response)

Tokens {{dotted.path}} resolve against a per-execution context:
    ref.*    data/seed-refs.json (named seed ids)
    <bind>.* values the case binds ("bind": {"v": "pool:standardCars"})
    <save>.* values a previous step saved from its response ("save": {"apptId": "id"})
    run.*    run.id (unique per locust process) and run.user (unique per simulated user)
"""
import json
import random
import re
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

BASE = Path(__file__).resolve().parents[1]
TOKEN = re.compile(r"\{\{\s*([\w.\-]+)\s*\}\}")

REFS = json.loads((BASE / "data" / "seed-refs.json").read_text())


def suite_names():
    return sorted(p.stem for p in (BASE / "input").glob("*.json"))


def load_suite(name):
    """input/<name>.json + expected/<name>.json → cases, each with its expectation attached."""
    inp = json.loads((BASE / "input" / f"{name}.json").read_text())
    exp = json.loads((BASE / "expected" / f"{name}.json").read_text())
    expected = {c["id"]: c for c in exp["cases"]}
    missing = [c["id"] for c in inp["cases"] if c["id"] not in expected]
    extra = sorted(set(expected) - {c["id"] for c in inp["cases"]})
    if missing or extra:
        raise ValueError(f"{name}: no expected output for {missing}, expected output without input for {extra}")

    for case in inp["cases"]:
        out = expected[case["id"]]
        if "steps" in case:
            step_exp = {st["id"]: st["expect"] for st in out.get("steps", [])}
            for step in case["steps"]:
                if step["id"] not in step_exp:
                    raise ValueError(f"{name}: {case['id']}/{step['id']} has no expected output")
                step["expect"] = step_exp[step["id"]]
        elif "expect" in out:
            case["expect"] = out["expect"]
        else:
            raise ValueError(f"{name}: {case['id']} has no 'expect'")
        if "aggregate" in out:
            case["aggregate"] = out["aggregate"]
        case["expectedGoal"] = out.get("goal")
        case.setdefault("weight", 1)
        case["_suite"] = inp["suite"]
    inp["files"] = (f"input/{name}.json", f"expected/{name}.json")
    return inp


def lookup(ctx, path):
    cur = ctx
    for part in path.split("."):
        if isinstance(cur, list):
            cur = cur[int(part)]
        elif isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            raise KeyError(f"unknown token {{{{{path}}}}}")
    return cur


def render(value, ctx):
    """Replace tokens; a string that is only one token keeps the value's own type."""
    if isinstance(value, str):
        whole = TOKEN.fullmatch(value)
        if whole:
            return lookup(ctx, whole.group(1))
        return TOKEN.sub(lambda m: str(lookup(ctx, m.group(1))), value)
    if isinstance(value, list):
        return [render(v, ctx) for v in value]
    if isinstance(value, dict):
        return {k: render(v, ctx) for k, v in value.items()}
    return value


def _local(tz, days, hhmm):
    zone = ZoneInfo(tz)
    day = datetime.now(zone).date() + timedelta(days=days)
    h, m = map(int, hhmm.split(":"))
    return datetime(day.year, day.month, day.day, h, m, tzinfo=zone)


def _iso(dt):
    return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def bind_one(spec, ctx):
    """One binding. Strings: "pool:<name>", "pick:<name>:<index token>", "uuid".
    Objects: {"date": {...}}, {"at": {...}}, {"slot": {...}} — times in the dealership's zone."""
    if isinstance(spec, str):
        kind, _, arg = spec.partition(":")
        if kind == "uuid":
            return str(uuid.uuid4())
        if kind == "pool":
            return random.choice(REFS["pools"][arg])
        if kind == "pick":                      # deterministic pick: pick:<pool>:<index>
            pool, _, idx = arg.partition(":")
            items = REFS["pools"][pool]
            return items[int(render(idx, ctx)) % len(items)]
        raise ValueError(f"unknown bind {spec!r}")
    (kind, a), = spec.items()
    if kind == "shift":                         # an earlier bind moved by N minutes
        base = datetime.fromisoformat(ctx[a["from"]].replace("Z", "+00:00"))
        return _iso(base + timedelta(minutes=a["minutes"]))
    # "days": "run+N" → a day 30..329 days ahead picked by the run id, plus N: every user of
    # one run gets the same day, the next run most likely a fresh one (race cases, §4.6)
    days = a.get("days")
    if isinstance(days, list):                  # [min, max] → a random day per request
        days = random.randint(*days)
    elif isinstance(days, str) and days.startswith("run"):
        days = 30 + int(ctx["run"]["id"], 16) % 300 + int(days[3:] or 0)
    if kind == "date":                          # local calendar date, N days from today
        return _local(a["tz"], days, "00:00").date().isoformat()
    if kind == "at":                            # fixed local time on day N → UTC instant
        return _iso(_local(a["tz"], days, a["time"]))
    if kind == "slot":                          # random local start on the slot grid → UTC instant
        day = random.randint(*a["days"])
        open_h, open_m = map(int, a["open"].split(":"))
        last_h, last_m = map(int, a["lastStart"].split(":"))
        steps = ((last_h * 60 + last_m) - (open_h * 60 + open_m)) // a["stepMinutes"]
        start = _local(a["tz"], day, a["open"]) + timedelta(minutes=a["stepMinutes"] * random.randint(0, steps))
        return _iso(start)
    raise ValueError(f"unknown bind {spec!r}")


def bind(case, ctx):
    for name, spec in case.get("bind", {}).items():
        ctx[name] = bind_one(spec, ctx)
    return ctx


def new_context(run_id, user_no):
    return {"ref": REFS, "run": {"id": run_id, "user": user_no}}
