"""Load tests for the Unified Service Scheduler (Spec06).

Every request comes from input/<suite>.json, and what must come back from
expected/<suite>.json (joined by case id, lt/fixtures.py). The users below only decide *who* sends *which* suites, and how often.

    LOADTEST_PROFILE=smoke|load|stress|spike|abuse|race|ramp|book-ramp   (default: smoke)

Run through docker-compose.loadtest.yml; see Spec06 §7.
"""
import json
import os
import random
import uuid
from itertools import count

import gevent
from gevent.event import Event
from locust import HttpUser, LoadTestShape, between, constant, constant_throughput, events, task

from lt import verdict
from lt.checks import check
from lt.fixtures import BASE, TOKEN, bind, load_suite, new_context, render, suite_names

PROFILE = os.getenv("LOADTEST_PROFILE", "smoke")
RUN_ID = uuid.uuid4().hex[:8]
THRESHOLDS = json.loads((BASE / "thresholds.json").read_text())
_user_no = count()


def _default_name(req):
    # "/api/v1/appointments/{{a.id}}/cancel" -> "/api/v1/appointments/{id}/cancel"
    return TOKEN.sub(lambda m: "{" + m.group(1).split(".")[-1] + "}", req["path"])


class ScenarioUser(HttpUser):
    """Runs the cases of `suites`, picked by their weight."""
    abstract = True
    suites = ()
    fixed_client_ip = None

    def on_start(self):
        self.no = next(_user_no)
        self.cases = [c for s in self.suites for c in load_suite(s)["cases"]]
        # Each simulated customer is its own client for the per-IP rate limiter (§4.5):
        # 198.18.0.0/15 is the RFC 2544 benchmarking range, never a real client
        self.client_ip = self.fixed_client_ip or f"198.18.{random.randint(0, 255)}.{random.randint(1, 254)}"

    # The only @task: Locust also collects @task methods of parent classes, so subclasses
    # override run_case() instead of declaring their own task
    @task
    def tick(self):
        self.run_case()

    def run_case(self):
        case = random.choices(self.cases, weights=[c["weight"] for c in self.cases])[0]
        self.execute(case)

    def execute(self, case):
        ctx = bind(case, new_context(RUN_ID, self.no))
        for step in case.get("steps") or [case]:
            if not self.send(case, step, ctx):
                break

    def send(self, case, step, ctx):
        req = render(step["request"], ctx)
        headers = {"X-Forwarded-For": self.client_ip, "X-Request-Id": f"lt-{RUN_ID}-{uuid.uuid4().hex[:12]}",
                   **req.get("headers", {})}
        name = step["request"].get("name") or _default_name(step["request"])
        with self.client.request(req["method"], req["path"], params=req.get("query"), json=req.get("body"),
                                 headers=headers, name=name, catch_response=True) as resp:
            try:
                body = resp.json()
            except ValueError:
                body = None
            errs = check(render(step["expect"], ctx), resp.status_code, resp.headers, body, resp.elapsed.total_seconds() * 1000)
            label = case["id"] + (f"/{step['id']}" if "steps" in case else "")
            if errs:
                resp.failure(f"{label}: " + "; ".join(errs))
            else:
                resp.success()
            if "aggregate" in case:
                same = case["aggregate"].get("sameValue")
                verdict.AGGREGATES[case["id"]].append((resp.status_code, (body or {}).get(same) if same else None))
            for key, path in step.get("save", {}).items():
                ctx[key] = (body or {}).get(path)
            # A setup step may get a valid refusal (car busy, slot full): it passes, but the
            # case stops, since the next steps need what this step was meant to create
            go_on = step["expect"].get("continueOn")
            return not errs and (go_on is None or resp.status_code in go_on)


# ---- who sends what (Spec06 §4) ----------------------------------------------------------

class SmokeUser(ScenarioUser):
    """One user walking every smoke case in order, once per second: is the stack wired up?"""
    suites = ("00-smoke",)
    wait_time = constant(1)

    def run_case(self):
        for case in self.cases:
            self.execute(case)


class BrowsingCustomer(ScenarioUser):
    """Most traffic: customers comparing slots again and again (the cache's job, D1)."""
    weight = 8
    suites = ("10-catalogue", "20-availability")
    wait_time = between(1, 3)


class BookingCustomer(ScenarioUser):
    """Fewer, heavier writes: book, look up, reschedule, cancel."""
    weight = 2
    suites = ("30-booking", "40-lifecycle")
    wait_time = between(2, 5)


class RampCustomer(ScenarioUser):
    """Exactly one request per second per user, so users = requests/s: RampShape caps the
    run at RAMP_MAX_RPS (100) and the RPS curve is the user curve."""
    suites = ("70-ramp",)
    wait_time = constant_throughput(1)


class BookingRampCustomer(RampCustomer):
    """Same 1 case per second as RampCustomer, but on the booking API (Spec07): RampShape
    drives 0 → RAMP_MAX_RPS with most requests being POST /appointments."""
    suites = ("80-book-ramp",)


class AbusiveClient(ScenarioUser):
    """One client hammering availability from a single IP: must get 429, not slow everyone."""
    suites = ("50-rate-limit",)
    fixed_client_ip = "198.19.255.1"
    wait_time = constant_throughput(50)


class RaceUser(ScenarioUser):
    """N users released at the same instant on the same slot (double-booking, §4.6).
    Standalone only: the gate and the counts live in this process."""
    suites = ("60-race",)
    wait_time = constant(0)
    gate, arrived, done = Event(), [0], [0]

    def run_case(self):
        # One shot per user; a finished user idles until the runner quits
        if getattr(self, "fired", False):
            gevent.sleep(1)
            return
        self.fired = True
        users = self.environment.parsed_options.num_users
        RaceUser.arrived[0] += 1
        if RaceUser.arrived[0] >= users:
            RaceUser.gate.set()
        RaceUser.gate.wait(timeout=60)
        for case in self.cases:                 # same order for everyone: they race per case
            self.execute(case)
            gevent.sleep(0.5)                   # let every user finish this case before the next
        RaceUser.done[0] += 1
        if RaceUser.done[0] >= users:
            gevent.spawn_later(1, self.environment.runner.quit)


PROFILES = {
    "smoke": (SmokeUser,),
    "load": (BrowsingCustomer, BookingCustomer),
    "stress": (BrowsingCustomer, BookingCustomer),
    "spike": (BrowsingCustomer, BookingCustomer),
    "abuse": (AbusiveClient,),
    "race": (RaceUser,),
    "ramp": (RampCustomer,),
    "book-ramp": (BookingRampCustomer,),
}
for _cls in (SmokeUser, BrowsingCustomer, BookingCustomer, RampCustomer, BookingRampCustomer, AbusiveClient, RaceUser):
    _cls.abstract = _cls not in PROFILES[PROFILE]
del _cls                                        # Locust collects classes from module globals


# ---- load shapes: only defined for the profiles that use them ----------------------------

class _StagedShape(LoadTestShape):
    abstract = True
    stages = ()                                 # (until second, users, spawn rate)

    def tick(self):
        t = self.get_run_time()
        return next(((u, r) for end, u, r in self.stages if t < end), None)


if PROFILE == "stress":
    class StressShape(_StagedShape):
        """Step up until it breaks: where does p95 leave the budget? (§4.3)"""
        abstract = False
        stages = ((60, 50, 10), (120, 100, 10), (180, 200, 20), (240, 300, 20), (300, 400, 20), (330, 0, 50))

elif PROFILE == "spike":
    class SpikeShape(_StagedShape):
        """Calm, sudden 10x burst, calm again: does it recover without errors? (§4.4)"""
        abstract = False
        stages = ((60, 20, 5), (90, 200, 100), (150, 200, 100), (210, 20, 50), (240, 0, 20))

elif PROFILE in ("ramp", "book-ramp"):
    class RampShape(LoadTestShape):
        """Linear ramp 0 → RAMP_MAX_RPS requests/s, hold at the top, then stop.
        One RampCustomer = 1 request/s, so the target user count is the target RPS."""
        max_rps = int(os.getenv("RAMP_MAX_RPS", "100"))
        ramp_s = int(os.getenv("RAMP_UP_SECONDS", "300"))
        hold_s = int(os.getenv("RAMP_HOLD_SECONDS", "180"))

        def tick(self):
            t = self.get_run_time()
            if t < self.ramp_s:
                return max(1, round(self.max_rps * t / self.ramp_s)), self.max_rps
            if t < self.ramp_s + self.hold_s:
                return self.max_rps, self.max_rps
            return None


@events.init.add_listener
def _show_test_data(environment, **_):
    """Print which JSON files this profile loads, before any request is sent."""
    suites = sorted({s for u in PROFILES[PROFILE] for s in u.suites})
    print(f"Profile '{PROFILE}' (run {RUN_ID}) loads:")
    for name in suites:
        suite = load_suite(name)              # fails fast if input and expected do not match
        print(f"  {suite['files'][0]:28} → {suite['files'][1]:31} {len(suite['cases'])} cases")


@events.quitting.add_listener
def _verdict(environment, **_):
    if environment.runner is None or type(environment.runner).__name__ == "WorkerRunner":
        return
    aggregates = [c for u in PROFILES[PROFILE] for s in u.suites for c in load_suite(s)["cases"] if "aggregate" in c]
    report = verdict.evaluate(environment, PROFILE, THRESHOLDS, aggregates)
    print(json.dumps(report, indent=2, ensure_ascii=False))
    if not report["ok"]:
        environment.process_exit_code = 1
