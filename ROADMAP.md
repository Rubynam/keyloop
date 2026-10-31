# Roadmap — Scenario A: The Unified Service Scheduler

> Keyloop Technical Assessment · Domain: **Ownership**
> Brief overview only — details to be filled in per phase.

📊 Diagram: [`docs/diagrams/roadmap/roadmap-overview.mermaid`](docs/diagrams/roadmap/roadmap-overview.mermaid)

---

## Phase 1 — Discovery & System Design

### 1.1 Key Definitions

#### Appointment Scheduler
A system that lets customers book a visit to a dealership's service department, replacing manual booking (phone calls, paper, spreadsheets). It must:
- Accept a request: who, which vehicle, which service, which dealership, what time.
- Check whether the workshop can actually handle it at that time.
- If yes, create and persist a confirmed appointment.

#### Resource Constrained Booking
A booking succeeds **only if enough limited resources are available for the requested time window**:
- Each dealership has a finite number of service bays and technicians.
- Each resource can serve **one** appointment at a time.

So the system does not just ask *"Is 10:00 free?"* but *"From 10:00 to 11:30, is there a bay **and** a qualified technician that are both free for the whole window?"*
Unlike a restaurant table booking (one resource type), this requires **two resource types to be free simultaneously**.

### 1.2 Booking Request Inputs

> "Request a service appointment for a specific vehicle, service type, and dealership at a desired time."

| Input | Meaning | Impact on booking |
|---|---|---|
| **Vehicle** | The specific car to be serviced (VIN / plate, make, model). Belongs to a **customer**. | Identifies whose appointment it is. A vehicle should not have two overlapping appointments. |
| **Service type** | e.g. oil change, tyre rotation, periodic maintenance, diagnostics. | Determines **duration** (oil change 60 min, major service 180 min) and **required skill** → end time and who can do the job. |
| **Dealership** | The specific branch / workshop the customer wants to visit. | Restricts the **resource pool** to that dealership's bays and technicians; also defines opening hours and time zone. |
| **Desired time** | The start time chosen by the customer. | `start + serviceType.duration` → the window `[start, end)` to check. |

Example: *Vehicle VIN `WVW123…` (owner: Customer A) wants an oil change (60 min) at Dealership District 7 at 10:00 on 5 Nov* → check the window 10:00–11:00 at District 7.

### 1.3 Required Resources

> "Check for the availability of both a ServiceBay and a qualified Technician for the entire service duration."

#### ServiceBay
A **physical workspace** in the workshop (a slot with a lift and tools) where the vehicle is parked while being serviced.
- Holds **one vehicle** at a time.
- Occupied for the **entire** service duration.
- Each bay **takes a set of vehicle types** (`GASOLINE_CAR`, `ELECTRIC_CAR`, `DIESEL_TRUCK`, …) — see [D2](#d2--vehicle-types-as-data-bays-and-technicians-mm-). A vehicle can only use a bay that takes its type (a truck needs a heavy-duty lift, an EV needs an insulated / high-voltage bay; a hybrid fits both a standard and a high-voltage bay).

#### Qualified Technician
A **technician whose skills / certifications match the service type**.
- "Qualified" means not every free technician can take the job (an oil-change-only technician cannot do a gearbox repair).
- Many-to-many relationship: one technician ↔ many service types, one service type ↔ many technicians.
- Also certified per **vehicle type** (e.g. high-voltage certification for EV, heavy-vehicle certification for trucks).
- Works on **one** appointment at a time.

#### "For the entire service duration"
Both resources must be free for the **whole** window, not just at the start:

```
Request:      [10:00 ──────── 11:00)
Bay 1:   [09:00 ── 10:30)              ✗ overlaps 10:00–10:30
Bay 2:                                 ✓ free
Tech A:            [10:45 ── 12:00)    ✗ overlaps 10:45–11:00
Tech B:  (no oil-change skill)         ✗ not qualified
Tech C:                                ✓ free + qualified
→ Result: Bay 2 + Tech C → appointment confirmed
```

If no valid **(bay, technician) pair** exists → reject the request.

### 1.4 Ambiguities → Assumptions to Decide
- [x] Are bays typed / specialised? → **Yes, by vehicle type**; one bay may take several types (M:N), see D2.
- [x] Can one vehicle have overlapping appointments? → **No** (exclusion constraint on `vehicle_id`, Spec03 DB-03).
- [ ] Buffer time between consecutive appointments?
- [ ] Are customer / vehicle pre-existing or created during booking?

### 1.5 Design Documents
- [High-Level Design](docs/high-level-design.md) — system context, containers, main flows, design principles.
- [Detail-Level Design](docs/detail-level-design.md) — components, ERD + constraints, API, sequences (availability, booking, cancel/reschedule, outbox relay), availability algorithm, state machines, Redis keys, errors, observability, deployment.

### 1.6 Remaining Phase 1 Tasks
<!-- TODO: resource selection strategy, slot step, tech stack justification -->

## Phase 2 — Scaffolding ✅
Spec: [`planing/Spec01.md`](planing/Spec01.md)
- docker-compose: `postgres:18-alpine` (+ `btree_gist`, schema `batch`), `redis:8-alpine` (cache only).
- Gradle multi-project (Kotlin DSL, wrapper, version catalog, Java 25, Spring Boot 4.1):
  - `server-api` — REST API, owns Flyway migrations (`V1__baseline`: `outbox_events`).
  - `scheduler-batch` — Spring Batch (metadata in schema `batch`), `heartbeatJob` sample, ShedLock for multi-replica safety.
- One Dockerfile per module (multi-stage, layered jar, non-root); each app starts, stops and scales on its own.

Spec: [`planing/Spec02.md`](planing/Spec02.md)
- `nginx:1.30-alpine` load balancer in front of 2× `server-api`; replicas discovered at runtime (`upstream … resolve` + Docker DNS), no reload on scale.
- Networks: `edge` / `lb-net` (internal) / `data-net`; nginx cannot reach Postgres/Redis.
- API: forwarded headers, graceful shutdown, `X-Request-Id` in MDC.

## Phase 3 — Core Features
Spec: [`planing/Spec03.md`](planing/Spec03.md)
- Flyway `V2__domain_schema`: all ERD tables; exclusion constraints on technician / bay / vehicle; composite FKs for vehicle-type fit, skill, certification (D2) and dealership.
- Vehicle types are data: `vehicle_types` lookup + `car_info` model catalogue ("Vios — gasoline-powered car — TOYOTA"); bays and technicians ↔ vehicle types are M:N (D2).
- `AppointmentStatus` is a Java enum, no DB `CHECK` list. All ids are UUIDv7 (`uuidv7()`).
- `seeding/`: 100 rows per table with tagged edge cases (time zones, DST, durations, capacity, lifecycle, outbox states).

## Phase 4 — Testing
Spec: [`planing/Spec06.md`](planing/Spec06.md)
- Locust in Docker (`docker-compose.loadtest.yml`, `locustio/locust:2.46.7`), hits nginx on `edge` only; standalone runner + master/workers with UI.
- Test data as JSON, two files per suite: `loadtest/input/` (request, goal, requirement) and `loadtest/expected/` (status, headers, body, latency, counts), joined by case id.
- Profiles smoke / load / stress / spike / abuse / race; verdict JSON + exit code from `thresholds.json`; `verify/invariants.sql` proves no double booking in the DB.
- Smoke passes today; other profiles are the executable contract for the booking / availability API.

## Phase 5 — Build for the Future
<!-- TODO -->

## Phase 6 — Docs & Client Stub
<!-- TODO -->

## Phase 7 — Submission
<!-- TODO -->

## Decisions

### D1 — Data store & double-booking prevention ✅

**Problem:** Prevent double-booking of technicians and bays under concurrent requests, while handling heavy / spammy availability reads (users repeatedly querying vehicle + time).

**Decision:**
- **PostgreSQL = single source of truth (write path).** `appointments` is a regular table; overlap is blocked by `tstzrange(start_time, end_time, '[)')` + exclusion constraints (`btree_gist`) on `technician_id` and `service_bay_id`, ignoring `CANCELLED`. Overlap check and insert are atomic in one transaction.
- **Redis = read-side cache (read path only).** Caches availability results to absorb heavy reads. **Never** used to decide whether a booking is valid.
- **Cache-aside on reads, event-driven invalidation on writes** via a transactional outbox → a version key is incremented, never the cached value updated (the value is derived data; updating it would risk out-of-order overwrites).

**Why it is safe:** a stale cache can only show a slot as free when it is not → the booking still hits the DB constraint → `409 Conflict`. Stale reads cost UX, never correctness.

**Read path — cache-aside** (spec: [`spec-availability-ops`](_bmad-output/specs/spec-availability-ops/SPEC.md), details in its `cache-read-path.md`):
- [ ] Key: `avail:{dealershipId}:{serviceTypeId}:{vehicleTypeId}:{date}` — no VIN in the key (availability depends only on the vehicle type, see D2) → higher hit rate.
- [ ] Versioned physical key: `avail:{dealershipId}:{date}:v{ver}:{serviceTypeId}:{vehicleTypeId}`, with `ver = GET avail:ver:{dealershipId}:{date}` read **before** querying PostgreSQL. A missing version key reads as `0`.
- [ ] Cached value = the **full day's** slots; slots with `start < now` are dropped per request, after the cache (never serve a slot that has started).
- [ ] Miss → compute from PostgreSQL → `SET` with TTL 30 s + random padding 0–30 s (spreads expiry so keys built together do not expire together). TTL and padding come from config.
- [ ] Stampede protection (request coalescing): lock `avail:lock:{dealershipId}:{date}:v{ver}:{serviceTypeId}:{vehicleTypeId}` via `SET NX PX 2s` with an owner token; the version is in the lock key so an old version's lock cannot block the next rebuild. Others poll the cache up to 100 ms (config), then compute from PostgreSQL without writing the cache. Release by a Lua compare-and-delete run through `StringRedisTemplate`.
- [ ] Slot-list TTL (≤ 60 s) stays far shorter than the version-key TTL (2 days), so a version reset to `0` never meets an old `:v0:` entry.
- [ ] Fail-open: Redis down → read from PostgreSQL.

**Write path — event-driven invalidation (transactional outbox):**

📊 Diagram: [`docs/diagrams/roadmap/d1-outbox-invalidation.mermaid`](docs/diagrams/roadmap/d1-outbox-invalidation.mermaid)

- [ ] `outbox_events(id, aggregate_type, aggregate_id, event_type, payload jsonb, created_at, processed_at, attempts, next_attempt_at, failed_at, last_error)` written **in the same transaction** as the appointment change.
- [ ] Events: `AppointmentBooked`, `AppointmentCancelled`, `AppointmentRescheduled` (old + new date), later `BayBlocked`, `TechnicianUnavailable`, `BusinessHoursChanged`.
- [ ] Relay: plain `@Scheduled` polling worker (not a Batch job, [Spec04](planing/Spec04.md) Q1) with `FOR UPDATE SKIP LOCKED` (safe with multiple instances). `LISTEN/NOTIFY` deferred: a 500 ms poll is enough (Spec04 §11).
- [ ] At-least-once delivery is fine: invalidation (`INCR`) is idempotent in effect → no dedup needed.
- [ ] Retry: 1 try + max 3 retries, fixed 2 s apart, then FAILED + English ERROR log; the booking is never changed (Spec04 §4.3).
- [ ] Cleanup (daily Spring Batch job): PROCESSED > 7 days, FAILED > 30 days, Batch metadata > 7 days (Spec04 CLN-02/03).
- [ ] Metrics: outbox pending count, relay lag, relay failures. *(deferred — no Prometheus/Grafana yet; tracing via Jaeger only)*
- [ ] Future: swap the relay for CDC (Debezium) if events must also feed other systems.

**Abuse protection (separate concern — cache absorbs load, it does not stop abuse):**
- [ ] Rate limiting per client (Redis token bucket) for all `/api/v1`, 20 req/s, burst 40, from config — **its own spec** (see `_bmad-output/implementation-artifacts/deferred-work.md`). Key on the client IP from `X-Forwarded-For` (set by nginx) — `remoteAddr` would be nginx for everyone (Spec02 API-14).
- [x] Bound query range: max 14 days per request ([`spec-check-availability`](_bmad-output/specs/spec-check-availability/SPEC.md)).

**Rejected:**
- TimescaleDB — appointments are not time-series (low volume, mutable); hypertables restrict exclusion / unique constraints across chunks.
- Redis as booking lock / source of truth — dual-write inconsistency, async replication can lose reservations, lock lease issues without fencing tokens.
- Write-through cache — the value is derived from many tables, one booking affects every service type at that dealership and date, and concurrent writes can overwrite newer values with stale ones.
- Direct invalidation right after commit — lost if the app crashes between commit and the Redis call; the outbox makes invalidation part of the transaction.

### D2 — Vehicle types as data; bays and technicians M:N ✅

**Problem:** Not every bay / technician can service every vehicle. A truck needs a heavy-duty lift; an EV needs an insulated bay and a high-voltage certified technician. A fixed 3-value enum (`PRIVATE_CAR`, `TRUCK`, `EV`) was too coarse (a diesel car and a hybrid are different jobs) and needed a code change for every new category.

**Decision (Spec03):**
- `vehicle_types(id, code, name)` — a **lookup table**, e.g. `GASOLINE_CAR` "Gasoline-powered car", `DIESEL_CAR`, `HYBRID_CAR`, `ELECTRIC_CAR`, `DIESEL_TRUCK`, `ELECTRIC_TRUCK`. A new category is a row, not a release.
- `car_info(id, name, vehicle_type_id, brand)` — **model catalogue**: *Vios → gasoline-powered car → TOYOTA*. The same name may exist with two powertrains (Corolla Cross gasoline / hybrid).
- `vehicles.car_info_id` — a vehicle is an instance of a model; its type comes from the model. `vehicles.vehicle_type_id` is a copy, kept equal by FK `(car_info_id, vehicle_type_id) → car_info(id, vehicle_type_id)`.
- `service_bay_vehicle_types(service_bay_id, vehicle_type_id)` — **M:N**: a standard bay takes gasoline, diesel and hybrid cars; a high-voltage bay takes EVs and hybrids; a heavy HV bay takes diesel and electric trucks.
- `technician_vehicle_types(technician_id, vehicle_type_id)` — certifications, M:N.
- **Qualified technician** = has skill for the service type **AND** is certified for the vehicle type.
- Booking derives the type from the vehicle; the client never chooses it.
- `appointments.vehicle_type_id` is stored, and the DB itself checks the fit with composite FKs: `(service_bay_id, vehicle_type_id) → service_bay_vehicle_types`, `(technician_id, vehicle_type_id) → technician_vehicle_types`, `(technician_id, service_type_id) → technician_skills`, `(vehicle_id, vehicle_type_id) → vehicles`. A truck in a car bay, or a technician without the skill, is rejected by PostgreSQL.
- **Bay selection:** when several bays fit, pick the bay that takes the **fewest vehicle types** first, so flexible / scarce bays (a heavy bay that also takes diesel cars) stay free for vehicles only they can take.
- Availability and cache key are scoped per vehicle type: `avail:{dealershipId}:{date}:v{ver}:{serviceTypeId}:{vehicleTypeId}`. Version key stays per dealership + date (one bump invalidates all types — simple, and bookings are rare compared to reads).

```
Request: hybrid car (Corolla Cross HV), tyre rotation, 10:00–10:30, District 7
Bay 01 (gasoline, diesel, hybrid)   ✓ free
EV Bay 01 (electric, hybrid)        ✓ free     ← both fit; same type count → either
Heavy Bay 04 (diesel truck, diesel car) ✗ does not take hybrids
Tech A: skill ✓, hybrid cert ✗      ✗ not qualified
Tech C: skill ✓, hybrid cert ✓, free ✓
→ Bay 01 + Tech C
```

**Trade-offs:**
- Qualification FKs make history strict: a certification or skill row that past appointments use cannot be deleted. Remove it only after those appointments are archived, or keep it and stop new bookings in the service layer.
- A model's vehicle type cannot change once vehicles use it (composite FK, no cascade) — correct a wrong catalogue entry with a deliberate migration.

**Rejected / future:**
- Java enum / DB `CHECK` list for vehicle types — needs a code change per new category (was the first Spec03 version).
- One bay = one type — a diesel car could not use a gasoline car's bay.
- Duration per (service type, vehicle type) — e.g. truck oil change longer. Future: `service_type_durations(service_type_id, vehicle_type_id, minutes)` with fallback to the default.

## Open Decisions
- [x] Resource selection strategy — bays: fewest vehicle types first (D2); technicians: fewest appointments that local day first; up to 3 pairs, diagonal so each differs in bay and technician (`spec-availability-ops/candidate-selection.md`)
- [ ] Data flow
