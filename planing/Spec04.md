# Spec04 — Load Testing with Locust

> Keyloop Technical Assessment · Scenario A: The Unified Service Scheduler
> Roadmap: **Phase 4 — Testing (load / concurrency)** · Builds on [Spec01](Spec01.md), [Spec02](Spec02.md), [Spec03](Spec03.md)
> Status: 🟡 Harness implemented — smoke passes today; the other profiles wait for the booking / availability API · Owner: Dai Nam

---

## 1. Goal

Set up a **[Locust](https://locust.io/) load-testing harness in Docker** and write down, as data, **what each request sends and what must come back**. When Spec04 is done:

1. `docker compose -f docker-compose.yml -f docker-compose.loadtest.yml run --rm locust` runs a load test against **nginx** (`edge` network), like a real client, and **exits 0 only if the system met every target**.
2. Test data is JSON, in **two separate files per suite**, joined by case `id`:
   - `loadtest/input/<suite>.json` — the **input request** (what is sent), the **goal** of the case and the **requirement it verifies**;
   - `loadtest/expected/<suite>.json` — the **expected output** (status, headers, body, latency, counts).

   Python code decides only *who* sends *which* case and *how often*.
3. "Failure" in a Locust report means **"the system did not behave as specified"** — a `409` where `409` is expected is a success; a `200` where `400` is expected is a failure.
4. The tests answer the questions the design raised (ROADMAP D1, D2, Spec03 DB-01…03):
   - Do hot availability reads stay fast because of the cache? (§4.1)
   - Does booking stay correct and fast under concurrent writes? (§4.2)
   - Where does the system break, and does it break *gracefully* (4xx, not 5xx)? (§4.3, §4.4)
   - Is one abusive client throttled? (§4.5)
   - **Can two requests ever book the same technician, bay or vehicle at the same time?** (§4.6)

The booking / availability API is **not built yet** (only `/api/v1/ping`). This spec fixes its expected behaviour **before** it is built — the input + expected files are an executable contract (§3). Today `smoke` passes; every other profile fails with `status 404, expected …`, which is the correct verdict.

---

## 2. Scope

### In scope
- `loadtest/` at the project root: Locust code, input files, expected-output files, seed references, budgets, DB invariant checks, tools.
- `docker-compose.loadtest.yml`: a one-shot headless runner and a master + N workers setup with the web UI.
- Six profiles: `smoke`, `load`, `stress`, `spike`, `abuse`, `race`.
- An end-of-run verdict (`reports/verdict-<profile>.json`) and exit code.

### Out of scope
- Implementing the API endpoints (next spec). Changing them while they are being built is allowed — the input / expected files are updated in the same change.
- Metrics dashboards (Prometheus/Grafana) — deferred in ROADMAP; Locust's own HTML report is enough now.
- Load tests of `scheduler-batch` jobs (only observed indirectly: outbox backlog, INV-05).
- Running Locust from more than one machine.

---

## 3. Assumptions (the contract the expected outputs encode)

| ID | Assumption | Where it is used |
|---|---|---|
| A-1 | Endpoints and status codes as in DLD §3 / §11. Bodies are camelCase JSON: request `{customerId, vehicleId, dealershipId, serviceTypeId, startTime}`, response `{id, status, startTime, endTime, vehicleId, serviceBayId, technicianId, …}`; availability `{…, slots: [{start, end}]}`; reschedule `{newStartTime}`. | all suites |
| A-2 | Instants are **UTC ISO-8601 with `Z` and whole seconds** (`2026-11-03T01:00:00Z`). Availability `from` / `to` are **local dates** of the dealership. | AV-03, BK-01, LC-01 |
| A-3 | Slot step = **15 min**, slots start at opening time (DLD §8 "TBD, e.g. 15 min"). | AV-01, AV-03, AV-04 |
| A-4 | `POST /appointments` takes an `Idempotency-Key` header; same key → the same appointment, `201`. | BK-02, RC-02 |
| A-5 | "Busy now" = `409`; "never possible at this dealership" (no bay / technician takes the vehicle type or does the service) = `422`; window outside opening hours or start in the past = `400`. | BK-04…12 |
| A-6 | Rate limiter keys on the client IP from `X-Forwarded-For` (Spec02 API-14). For throughput profiles it runs with a budget high enough for the test; `abuse` runs with the default budget. | §4.5 |

If the implementation decides differently, change the expected file **and** this table in the same commit.

---

## 4. What we test — workload model

Users are classes in `loadtest/locustfile.py`; a class runs the cases of its suites, chosen by `weight`.

| User class | Share | Suites | Think time | Models |
|---|---|---|---|---|
| `BrowsingCustomer` | 80 % | `10-catalogue`, `20-availability` | 1–3 s | customers comparing slots again and again |
| `BookingCustomer` | 20 % | `30-booking`, `40-lifecycle` | 2–5 s | fewer, heavier writes |
| `SmokeUser` | — | `00-smoke` (all cases in order) | 1 s | "is the stack wired up?" |
| `AbusiveClient` | — | `50-rate-limit` | 50 req/s, one IP | a spammy script |
| `RaceUser` | — | `60-race` | released together | concurrent clicks on one slot |

Each simulated customer sends its own `X-Forwarded-For` from `198.18.0.0/15` (RFC 2544 benchmarking range), so the per-IP rate limiter sees many clients, not one Locust container.

### 4.1–4.6 Profiles

| Profile | Shape | Question it answers | Pass when |
|---|---|---|---|
| `smoke` | 1 user, 1 min | Harness → nginx → API wired; request id kept; actuator hidden | every case as expected, ping p95 ≤ 100 ms |
| `load` | e.g. 50 users, 10 min (`LOCUST_USERS`, `LOCUST_RUN_TIME`) | Normal busy day: cache absorbs reads, bookings stay fast and correct | budgets in §6.1, `< 1 %` unexpected, 0 × 5xx, invariants 0 rows |
| `stress` | steps 50 → 100 → 200 → 300 → 400 users, 1 min each | Where is the knee? Does overload give 4xx/latency, never 5xx? | `< 5 %` unexpected, 0 × 5xx; read the p95 curve in `reports/stress.html` |
| `spike` | 20 → 200 users in 1 s, hold 1 min, back to 20 | Recovery after a burst (nginx `least_conn`, connection pools) | `< 2 %` unexpected, 0 × 5xx, p99 bounded |
| `abuse` | 1 client, ~50 req/s | Is one client throttled with `429` + `Retry-After`? | ≥ 1 × 200 and ≥ 1 × 429, nothing else |
| `race` | 10 users held at a gate, released together | Double booking (§4.6) | exact counts per case (RC-01…03), invariants 0 rows |

`race` and `abuse` check **counts across users**, so they run **standalone** (one process), not with workers.

---

## 5. Input request + expected output

### 5.1 Layout (`loadtest/` at the project root)

```
loadtest/
├── Dockerfile                  locustio/locust:2.46.7 (+ tzdata), pinned
├── locust.conf                 defaults; per-run values come from LOCUST_* env
├── locustfile.py               user classes, profiles, load shapes, verdict hook
├── lt/
│   ├── fixtures.py             join input + expected, bind values, render {{tokens}}
│   ├── checks.py               compare a response with "expect"
│   └── verdict.py              budgets, aggregates → reports/verdict-<profile>.json
├── input/                      ← INPUT REQUESTS (what is sent, and why)
│   ├── 00-smoke.json           SM-01…05   infrastructure (passes today)
│   ├── 10-catalogue.json       CT-01…05   reference data
│   ├── 20-availability.json    AV-01…10   read path, cache, time zones, guards
│   ├── 30-booking.json         BK-01…12   write path, every refusal reason
│   ├── 40-lifecycle.json       LC-01…04   read-your-write, cancel, reschedule
│   ├── 50-rate-limit.json      RL-01      abuse
│   └── 60-race.json            RC-01…03   concurrency, exact counts
├── expected/                   ← EXPECTED OUTPUTS, same file names, same case ids
│   └── 00-smoke.json … 60-race.json
├── data/seed-refs.json         named seed ids (generated, do not edit)
├── thresholds.json             latency / error budgets per profile
├── verify/invariants.sql       DB checks after a run: every query → 0 rows
├── tools/build_seed_refs.py    seeding/sql → data/seed-refs.json
├── tools/check_scenarios.py    static check of input + expected (no network)
└── reports/                    HTML, CSV, verdict JSON (git-ignored)
```

### 5.2 How Locust takes the JSON in

```
input/30-booking.json ─┐                                   ┌─ data/seed-refs.json ({{ref.*}})
                       ├─ lt/fixtures.load_suite() ─ join ─┤
expected/30-booking.json┘   by case id + step id            └─ per execution: bind() → render()
                                    │                                        │
                                    ▼                                        ▼
                     user class picks a case by weight     request sent to nginx, response
                     (locustfile.py, §4)                   checked by lt/checks.check(expect)
                                                                             │
                                          mismatch → Locust failure "BK-03/overlap: status 201, expected [409]"
                                          end of run → reports/verdict-<profile>.json + exit code
```

1. **Load** — at start-up Locust prints which files the profile reads, e.g.
   `input/00-smoke.json → expected/00-smoke.json  5 cases`. A case with no expected output (or the reverse) stops the run before any request.
2. **Bind** — the case's values are made (`uuid`, random car from the seed, a slot time in the dealership's zone).
3. **Render** — `{{tokens}}` are replaced in the request **and** in the expected output (`"startTime": {"equals": "{{start}}"}`).
4. **Send + check** — the response is compared with the expected output; any difference is a failure with the case id.

### 5.3 One case, two files

`input/30-booking.json` — what is sent:

```json
{
  "id": "BK-03",
  "goal": "Same vehicle, same time, NEW key → 409: one car cannot be in two bays at once (DB-03) …",
  "verifies": ["Spec03 DB-03 ex_appointments_vehicle"],
  "weight": 3,
  "bind": { "v": "pool:standardCars", "k1": "uuid", "k2": "uuid",
            "start": { "slot": { "tz": "Asia/Ho_Chi_Minh", "days": [241, 360], "open": "08:00", "lastStart": "16:00", "stepMinutes": 15 } } },
  "steps": [
    { "id": "first",   "request": { "method": "POST", "path": "/api/v1/appointments", "headers": { "Idempotency-Key": "{{k1}}" },
                                    "body": { "vehicleId": "{{v.vehicleId}}", "startTime": "{{start}}", "…": "…" } } },
    { "id": "overlap", "request": { "…": "same vehicle and start, key {{k2}}" } }
  ]
}
```

`expected/30-booking.json` — what must come back, same `id`, same step ids:

```json
{
  "id": "BK-03",
  "goal": "Same vehicle, same time, NEW key → 409: … (identical to the input; the checker enforces it)",
  "steps": [
    { "id": "first",   "expect": { "status": 201 } },
    { "id": "overlap", "expect": { "status": 409, "body": [ { "path": "status", "equals": 409 } ] } }
  ]
}
```

| Field | Meaning |
|---|---|
| `goal` | **What the case proves**, in one sentence — the "why" behind the expected output. In both files, identical. Mandatory. |
| `verifies` | Requirement / decision ids it covers (Spec, DLD, ROADMAP). Mandatory. |
| `weight` | Relative frequency inside its user class (default 1). |
| `bind` | Values made once per execution. `uuid` · `pool:<name>` (random seed row) · `pick:<pool>:<index>` (deterministic) · `{"date": {tz, days}}` local date · `{"at": {tz, days, time}}` local time → UTC · `{"slot": {…}}` random start on the 15-min grid → UTC · `{"shift": {from, minutes}}`. `days` is a number, `[min, max]` (random) or `"run+N"` (same for every user of a run, new per run). |
| `request` | `method`, `path`, `query`, `headers`, `body`, `name` (the row in the report and the key in `thresholds.json`). |
| `steps` | *(input)* A flow instead of one request; `save: {"apptId": "id"}` keeps a response field for later steps. *(expected)* one `expect` per step id. A step that misses its expectation stops the flow. |
| *expected file:* `expect.status` | One code or a list of allowed codes. |
| `expect.onStatus` | Extra expectations per status, e.g. `201` must have `Location`, `409` must be a ProblemDetail. |
| `expect.headers` / `expect.body` | Rules: `equals`, `oneOf`, `matches` (regex), `type`, `exists`, `minItems`, `maxItems`, `each` (for every array item). `path` is dotted (`slots.0.start`, `""` = root). |
| `expect.maxLatencyMs` | Per-request latency limit (e.g. a `400` must be cheap). |
| `aggregate` | Expectation over **all** executions of the case: `allowed`, `exactly`, `atLeast`, `sameValue`. Used by `race` and `abuse`. |

Tokens `{{…}}` work in requests **and** expectations, so an expected value can be the input (`"startTime": {"equals": "{{start}}"}`). Sources: `ref.*` (seed-refs.json), bound names, saved names, `run.id`, `run.user`.

### 5.4 Test data

- Ids come from the **Spec03 seed** by name: `{{ref.dealerships.LONDON}}`, `{{ref.vehicles.HYDROGEN.vehicleId}}`. `data/seed-refs.json` is generated by `tools/build_seed_refs.py` from `seeding/sql/*.sql`; re-run it after `seeding/generate.py`.
- The seed's own edge rows (Spec03 §6) decide the expected outputs: London has **one** oil-change technician (capacity 1, RC-01); Express Kiosk is open **60 min** (AV-04); Kiritimati is **UTC+14** (AV-03); the hub has **no heavy high-voltage bay** (BK-11); nobody can do the hydrogen service (BK-12).
- Dates are **relative to the day of the run** (not the seed's fixed anchor), so the files do not expire. Writing suites use **separate day ranges** (BK-01 2–120, BK-02 121–240, BK-03 241–360, lifecycle 361–600, race "run" 30–331) so one case never takes another case's slot.

### 5.5 Case catalogue

| Suite | Cases → expected output (short) |
|---|---|
| 00-smoke | SM-01 ping `200 {"status":"ok"}` + `X-Upstream-Addr` · SM-02 own `X-Request-Id` echoed · SM-03 `/actuator/health` `UP` · SM-04 `/actuator/env` `404` · SM-05 unknown path → ProblemDetail `404` |
| 10-catalogue | CT-01 dealerships with tz + hours · CT-02 service types with `durationMinutes` · CT-03 exactly 8 vehicle types · CT-04 VIN → vehicle with `vehicleTypeId` = HYBRID_CAR · CT-05 unknown VIN `404` |
| 20-availability | AV-01 **hot** query `200`, slots on the 15-min grid · AV-02 **cold** random day `200` · AV-03 UTC+14 → exactly 29 slots, first = 08:00 local = 18:00Z previous UTC day · AV-04 60-min kiosk + 25-min job → exactly 09:00/09:15/09:30 · AV-05 hydrogen → `200 []` · AV-06 no bays → `200 []` · AV-07 15-day range `400` (≤ 200 ms) · AV-08 `to < from` `400` · AV-09 bad UUID / missing param `400` · AV-10 unknown dealership `404` |
| 30-booking | BK-01 random booking `201` (bay + technician, `Location`, UUIDv7 id) **or** `409` ProblemDetail with `traceId` · BK-02 same key twice → same id · BK-03 same car, same time, new key → `409` · BK-04 past `400` · BK-05 20:00 local `400` · BK-06 ends after closing `400` · BK-07 not the owner `400` · BK-08 missing fields `400` · BK-09 unknown dealership `404` · BK-10 hydrogen `422` · BK-11 electric truck at the hub `422` · BK-12 nobody has the skill `422` |
| 40-lifecycle | LC-01 book → read → reschedule +1 day → cancel → cancel again `409` → `CANCELLED` · LC-02 cancelled slot can be booked again `201` · LC-03 day list `200` · LC-04 unknown id `404` |
| 50-rate-limit | RL-01 one IP at 50 req/s → `200` then `429` + `Retry-After` + ProblemDetail, ≤ 100 ms |
| 60-race | RC-01 capacity 1, 10 cars → **exactly 1 × 201, 9 × 409** · RC-02 one key × 10 → **10 × 201, one id** · RC-03 one car, 10 keys, plenty of resources → **exactly 1 × 201** |

---

## 6. Pass / fail

### 6.1 Budgets (`thresholds.json`, profile `load`)

| ID | Request name | Budget | Why |
|---|---|---|---|
| NFR-01 | `/api/v1/availability [hot]` | p50 ≤ 30 ms, p95 ≤ 150 ms, p99 ≤ 400 ms, ≥ 500 requests, ≤ 0.1 % unexpected | cache hit path (D1) |
| NFR-02 | `/api/v1/availability [cold]` | p95 ≤ 600 ms, p99 ≤ 1.5 s | computed from PostgreSQL |
| NFR-03 | `/api/v1/appointments` (POST) | p95 ≤ 400 ms, p99 ≤ 1 s, ≥ 100 requests | transaction + retry over candidates |
| NFR-04 | `/api/v1/appointments/{id}`, catalogue | p95 ≤ 150 ms | simple reads |
| NFR-05 | all | ≤ 1 % responses not matching the expected output, **0 × 5xx** (nginx 503 included) | graceful behaviour |

The budgets are first targets on a laptop (Docker Desktop, 2 API replicas). Re-baseline them once the API exists; change numbers only with a note in the commit.

### 6.2 Verdict

At the end of a run the master (or the standalone process) writes `loadtest/reports/verdict-<profile>.json` (per-endpoint percentiles, fail ratio, 5xx count, aggregates, errors) and sets **exit code 1** if anything is missed. The HTML report shows each mismatch as `<case id>/<step>: <what differed>`, e.g. `BK-03/overlap: status 201, expected [409]`.

### 6.3 Database invariants (after `load`, `stress`, `spike`, `race`)

```bash
docker compose exec -T postgres psql -U scheduler -d scheduler < loadtest/verify/invariants.sql
```

Expected output: **every query returns 0 rows** — INV-01 technician / INV-02 bay / INV-03 vehicle overlaps among non-cancelled appointments, INV-04 appointment without outbox event (same transaction, D1), INV-05 outbox events older than 1 min still pending (relay keeps up). The API answering correctly is not enough: this proves the stored data is correct too.

---

## 7. Docker setup

`docker-compose.loadtest.yml` overlays the main compose file (same project, same networks). Its services have `profiles: [loadtest]`, so a plain `docker compose up` never starts them.

| Service | Mode | Notes |
|---|---|---|
| `locust` | standalone, headless, one shot | `run --rm`; exit code = verdict; HTML + CSV to `loadtest/reports/` |
| `locust-master` | master, web UI on `:8089`, class picker | `load` / `stress` / `spike` only |
| `locust-worker` | worker, `--scale locust-worker=N` | finds the master by DNS |

- Network: **`edge` only** — Locust reaches nginx like a real client and cannot reach `server-api`, Postgres or Redis directly.
- `./loadtest` is mounted, so scenario edits need no rebuild. The image is pinned (`locustio/locust:2.46.7`), so numbers from two runs come from the same tool.
- Settings: `LOADTEST_PROFILE`, `LOCUST_USERS`, `LOCUST_SPAWN_RATE`, `LOCUST_RUN_TIME`, `LOCUST_HOST` (default `http://nginx`), `LOCUST_UI_PORT`.

```bash
# static check of every scenario (no API needed)
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml run --rm --no-deps --entrypoint python locust tools/check_scenarios.py

# smoke (default profile)
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml run --rm locust

# load, 50 users for 10 minutes
LOADTEST_PROFILE=load LOCUST_USERS=50 LOCUST_SPAWN_RATE=5 LOCUST_RUN_TIME=10m \
  docker compose -f docker-compose.yml -f docker-compose.loadtest.yml run --rm locust

# race: exactly 10 users, all spawned at once
seeding/seed.sh
LOADTEST_PROFILE=race LOCUST_USERS=10 LOCUST_SPAWN_RATE=10 LOCUST_RUN_TIME=1m \
  docker compose -f docker-compose.yml -f docker-compose.loadtest.yml run --rm locust

# web UI with 4 workers → http://localhost:8089
LOADTEST_PROFILE=load docker compose -f docker-compose.yml -f docker-compose.loadtest.yml \
  --profile loadtest up -d --scale locust-worker=4 locust-master locust-worker
```

---

## 8. Requirements

| ID | Requirement |
|---|---|
| LT-01 | Locust runs in Docker from a pinned image; no host Python needed. |
| LT-02 | Load goes through nginx on the `edge` network only. |
| LT-03 | Every request is defined in `loadtest/input/<suite>.json` (`goal`, `verifies`, `request` / `steps`) and its expected output in `loadtest/expected/<suite>.json` (`expect` / `steps[].expect`, `aggregate`), joined by case id. No request or expectation is hard-coded in Python. |
| LT-03a | A case without expected output, an expected output without a case, or a step without `expect` stops the run at start-up. |
| LT-04 | A response that differs from `expect` is a Locust failure carrying the case id and the difference; an expected 4xx is a success. |
| LT-05 | Seed ids are referenced by name through `data/seed-refs.json`, generated from `seeding/sql`. |
| LT-06 | Dates are relative to the run day; writing cases use disjoint day ranges. |
| LT-07 | Each run writes `reports/verdict-<profile>.json` and exits non-zero when a budget, aggregate or the 5xx rule fails. |
| LT-08 | `tools/check_scenarios.py` renders every case without a network and fails on unknown tokens, duplicate ids, a missing `goal` / `verifies`, a goal that differs between the two files, input/expected files that do not pair up, or a budget for a request name no case sends. |
| LT-09 | `verify/invariants.sql` returns 0 rows after every writing profile. |
| LT-10 | Throughput users look like distinct clients (own `X-Forwarded-For`); the abusive client uses one IP. |
| LT-11 | Each request carries `X-Request-Id: lt-<run>-<n>`, so any slow or failed request can be found in the nginx and API logs. |

---

## 9. Acceptance Criteria

- [x] `docker compose … run --rm --no-deps --entrypoint python locust tools/check_scenarios.py` → `40 cases, 33 request names`, exit 0.
- [x] Removing one case from an `expected/` file → the checker reports `no expected output for ['BK-07']`, exit 1.
- [x] Start-up log lists `input/<suite>.json → expected/<suite>.json  N cases` for the profile's suites.
- [x] `docker compose … run --rm locust` (smoke) against today's stack → all SM cases as expected, `verdict-smoke.json` `"ok": true`, exit 0.
- [x] `race` with 10 users sends **exactly 10 requests per case** (gate works, no repeat), and fails today with `status 404 not allowed`.
- [x] `load` today exits 1 with `status 404, expected …` per case, 0 × 5xx — the harness reports a missing API as a failure, not as success.
- [x] `locust-master` + 2 × `locust-worker` → UI on `:8089`, 2 workers connected.
- [x] `verify/invariants.sql` on the seeded DB → 0 rows for INV-01…05.
- [ ] After the booking / availability API (next spec): `load` 10 min / 50 users → `"ok": true`.
- [ ] `race` → RC-01 `{201: 1, 409: 9}`, RC-02 `{201: 10}` one id, RC-03 `{201: 1, 409: 9}`; invariants 0 rows.
- [ ] `abuse` → at least one `429` with `Retry-After`.
- [ ] `stress` → knee found and written into this spec (users, req/s, p95 at the knee).

---

## 10. Trade-offs & Open Questions

- **`X-Forwarded-For` is client-controlled.** nginx uses `$proxy_add_x_forwarded_for`, which *appends* to what the client sent. If the rate limiter takes the left-most entry, anyone can rotate fake IPs and bypass it — the very trick the load test uses to simulate many clients. Decide in the rate-limiter spec: nginx overwrites (`X-Forwarded-For $remote_addr`) at the edge, or the API takes the right-most untrusted entry. Then the load test either keeps the header (trusted hop) or the limiter gets a load-test budget (A-6).
- **`race` / `abuse` are standalone only.** Their counts live in one process; workers would need a shared store. Ten users from one process is enough to make requests overlap.
- **BK-01 accepts 201 or 409.** The share of 409 under load (how full the hub gets) is not budgeted; the verdict only sees "expected or not". Could be added as an aggregate when the API exists.
- **The race day changes per run** (`"run"` → 30–331 days ahead); two runs landing on the same day (≈ 1 / 300) make RC-01 see 0 × 201. Reseed before a race run if exact numbers matter.
- **`HttpUser` (requests) instead of `FastHttpUser`.** Slower per core, but plain `requests` semantics for headers and JSON. Scale with workers if one container is the bottleneck (check Locust's CPU warning).
- **Budgets are laptop numbers.** Docker Desktop on macOS adds network overhead; compare runs on the same machine only.
- [ ] Should cache hit / miss be visible in a response header (`X-Cache: HIT`) so AV-01 can assert it, instead of only inferring it from latency?
- [ ] Should `stress` also scale `server-api` (`--scale server-api=4`) to show near-linear X-axis scaling (HLD 05-scaling)?
- [ ] Run `smoke` in CI on every PR (needs the compose stack in CI).
