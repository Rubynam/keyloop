"""Compares an HTTP response with a case's expected output (Spec06 §5.2).

Returns a list of human-readable mismatches; empty means the response is what the case
expects. A mismatch is reported to Locust as a failure, so "failures" in the Locust report
mean "the system did not behave as specified", not only "the request errored".
"""
import re

_MISSING = object()

TYPES = {"string": str, "number": (int, float), "integer": int, "boolean": bool,
         "array": list, "object": dict, "null": type(None)}


def at(doc, path):
    cur = doc
    for part in path.split(".") if path else []:
        if isinstance(cur, list) and part.isdigit() and int(part) < len(cur):
            cur = cur[int(part)]
        elif isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return _MISSING
    return cur


def check_value(label, actual, rule):
    errs = []
    if "exists" in rule and (actual is not _MISSING) != rule["exists"]:
        errs.append(f"{label}: exists={actual is not _MISSING}, expected {rule['exists']}")
        return errs
    if actual is _MISSING:
        return [f"{label}: missing"] if set(rule) - {"path", "exists"} else []
    if "equals" in rule and actual != rule["equals"]:
        errs.append(f"{label}: {actual!r} != {rule['equals']!r}")
    if "oneOf" in rule and actual not in rule["oneOf"]:
        errs.append(f"{label}: {actual!r} not in {rule['oneOf']!r}")
    if "matches" in rule and not re.search(rule["matches"], str(actual)):
        errs.append(f"{label}: {actual!r} !~ /{rule['matches']}/")
    if "type" in rule and not (isinstance(actual, TYPES[rule["type"]])
                               and not (rule["type"] in ("number", "integer") and isinstance(actual, bool))):
        errs.append(f"{label}: type {type(actual).__name__}, expected {rule['type']}")
    if "minItems" in rule and (not isinstance(actual, list) or len(actual) < rule["minItems"]):
        errs.append(f"{label}: fewer than {rule['minItems']} items")
    if "maxItems" in rule and isinstance(actual, list) and len(actual) > rule["maxItems"]:
        errs.append(f"{label}: more than {rule['maxItems']} items")
    if "each" in rule and isinstance(actual, list):
        for i, item in enumerate(actual):
            for sub in rule["each"]:
                errs += check_value(f"{label}.{i}.{sub.get('path', '')}", at(item, sub.get("path", "")), sub)
    return errs


def check(expect, status, headers, body, elapsed_ms):
    """expect = {"status": 200 | [201, 409], "headers": {...}, "body": [...], "maxLatencyMs": n}"""
    allowed = expect["status"] if isinstance(expect["status"], list) else [expect["status"]]
    if status not in allowed:
        return [f"status {status}, expected {allowed}"]

    errs = []
    # Per-status expectations: {"onStatus": {"409": {...}}} refines the shared ones
    exp = {**expect, **expect.get("onStatus", {}).get(str(status), {})}
    for name, rule in exp.get("headers", {}).items():
        errs += check_value(f"header {name}", headers.get(name, _MISSING), rule)
    for rule in exp.get("body", []):
        errs += check_value(f"body.{rule['path']}", at(body, rule["path"]) if body is not None else _MISSING, rule)
    if "maxLatencyMs" in exp and elapsed_ms > exp["maxLatencyMs"]:
        errs.append(f"latency {elapsed_ms:.0f} ms > {exp['maxLatencyMs']} ms")
    return errs
