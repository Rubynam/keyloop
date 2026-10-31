#!/usr/bin/env python3
"""Static check of input/*.json + expected/*.json (Spec06 LT-08): no network, no Locust run.

Renders every case and step once, so a typo in a token, a bind or a seed ref fails here
instead of as 100 % failures in the middle of a load run. Also checks ids are unique, every
case says what it tests, and every budgeted name in thresholds.json is sent by some case.

    docker compose -f docker-compose.yml -f docker-compose.loadtest.yml run --rm \
      --entrypoint python locust tools/check_scenarios.py
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lt.fixtures import BASE, bind, load_suite, new_context, render, suite_names  # noqa: E402

errors, ids, names, n = [], set(), set(), 0
if sorted(p.stem for p in (BASE / "expected").glob("*.json")) != suite_names():
    errors.append("input/ and expected/ do not hold the same suite files")
for name in suite_names():
    try:
        suite = load_suite(name)
    except ValueError as e:
        errors.append(str(e))
        continue
    if not suite.get("goal"):
        errors.append(f"{name}: suite has no goal")
    for case in suite["cases"]:
        n += 1
        cid = case.get("id", "?")
        if cid in ids:
            errors.append(f"{cid}: duplicate id")
        ids.add(cid)
        for key in ("goal", "verifies"):
            if not case.get(key):
                errors.append(f"{cid}: missing '{key}'")
        if case.get("expectedGoal") != case.get("goal"):
            errors.append(f"{cid}: goal differs between input and expected file")
        try:
            ctx = bind(case, new_context("0123abcd", 0))
            for step in case.get("steps") or [case]:
                render(step["request"], ctx)
                render(step["expect"], ctx)
                if "status" not in step["expect"]:
                    errors.append(f"{cid}: expect without status")
                names.add(step["request"].get("name") or step["request"]["path"])
                for key in step.get("save", {}):
                    ctx[key] = "00000000-0000-7000-8000-000000000000"
        except Exception as e:  # noqa: BLE001 — report every broken case, not only the first
            errors.append(f"{cid}: {type(e).__name__}: {e}")

thresholds = json.loads((BASE / "thresholds.json").read_text())
for profile, rules in thresholds["profiles"].items():
    for name in rules.get("endpoints", {}):
        if name not in names:
            errors.append(f"thresholds.{profile}: '{name}' is not the name of any request")

print(f"{n} cases, {len(names)} request names")
for e in errors:
    print("  ✗", e)
sys.exit(1 if errors else 0)
