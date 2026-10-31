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
- Bays are **pooled by vehicle type** (`PRIVATE_CAR`, `TRUCK`, `EV`) — see [D2](#d2--resource-pools-by-vehicle-type-). A vehicle can only use a bay from its own pool (a truck needs a heavy-duty lift, an EV needs an insulated / high-voltage bay).

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
- [x] Are bays typed / specialised? → **Yes, by vehicle type** (pool per `PRIVATE_CAR` / `TRUCK` / `EV`), see D2.
- [ ] Buffer time between consecutive appointments?
- [ ] Can one vehicle have overlapping appointments?
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
- Flyway `V2__domain_schema`: all ERD tables; exclusion constraints on technician / bay / vehicle; composite FKs for pool (D2) and dealership.
- Enum values (`VehicleType`, `AppointmentStatus`) defined in Java only, no DB `CHECK` lists.
- `seeding/`: 100 rows per table with tagged edge cases (time zones, DST, durations, capacity, lifecycle, outbox states).

## Phase 4 — Testing
<!-- TODO -->

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

**Read path — cache-aside:**
- [ ] Key: `avail:{dealershipId}:{serviceTypeId}:{vehicleType}:{date}` — no VIN in the key (availability depends only on the vehicle type pool, see D2) → higher hit rate.
- [ ] Versioned physical key: `avail:{dealershipId}:{date}:v{ver}:{serviceTypeId}:{vehicleType}`, with `ver = GET avail:ver:{dealershipId}:{date}` read **before** querying PostgreSQL.
- [ ] Miss → compute from PostgreSQL → `SET` with short TTL (e.g. 30–60s) + jitter.
- [ ] Stampede protection on cache miss (single-flight / short lock).
- [ ] Fail-open: Redis down → read from PostgreSQL.

**Write path — event-driven invalidation (transactional outbox):**

📊 Diagram: [`docs/diagrams/roadmap/d1-outbox-invalidation.mermaid`](docs/diagrams/roadmap/d1-outbox-invalidation.mermaid)

- [ ] `outbox_events(id, aggregate_type, aggregate_id, event_type, payload jsonb, created_at, processed_at)` written **in the same transaction** as the appointment change.
- [ ] Events: `AppointmentBooked`, `AppointmentCancelled`, `AppointmentRescheduled` (old + new date), later `BayBlocked`, `TechnicianUnavailable`, `BusinessHoursChanged`.
- [ ] Relay: polling worker with `FOR UPDATE SKIP LOCKED` (safe with multiple instances) + `LISTEN/NOTIFY` for low latency.
- [ ] At-least-once delivery is fine: invalidation (`INCR`) is idempotent in effect → no dedup needed.
- [ ] Retry with backoff; cleanup of processed rows (e.g. > 7 days).
- [ ] Metrics: outbox pending count, relay lag, relay failures. *(deferred — no Prometheus/Grafana yet; tracing via Jaeger only)*
- [ ] Future: swap the relay for CDC (Debezium) if events must also feed other systems.

**Abuse protection (separate concern — cache absorbs load, it does not stop abuse):**
- [ ] Rate limiting per client (Redis token bucket). Key on the client IP from `X-Forwarded-For` (set by nginx) — `remoteAddr` would be nginx for everyone (Spec02 API-14).
- [ ] Bound query range (e.g. max 14 days per request).

**Rejected:**
- TimescaleDB — appointments are not time-series (low volume, mutable); hypertables restrict exclusion / unique constraints across chunks.
- Redis as booking lock / source of truth — dual-write inconsistency, async replication can lose reservations, lock lease issues without fencing tokens.
- Write-through cache — the value is derived from many tables, one booking affects every service type at that dealership and date, and concurrent writes can overwrite newer values with stale ones.
- Direct invalidation right after commit — lost if the app crashes between commit and the Redis call; the outbox makes invalidation part of the transaction.

### D2 — Resource pools by vehicle type ✅

**Problem:** Not every bay / technician can service every vehicle. A truck needs a heavy-duty lift; an EV needs an insulated bay and a high-voltage certified technician.

**Decision:**
- `vehicle_type` enum: `PRIVATE_CAR`, `TRUCK`, `EV` (extensible, e.g. `MOTORBIKE`, `VAN`).
- `vehicle.vehicle_type` — each vehicle belongs to exactly one type.
- `service_bay.vehicle_type` — each bay belongs to exactly **one pool**.
- `technician_vehicle_type(technician_id, vehicle_type)` — a technician may be certified for several types.
- **Qualified technician** = has skill for the service type **AND** is certified for the vehicle type.
- Booking derives the pool from the vehicle (`vehicle.vehicle_type`); the client never chooses it.
- `appointment.vehicle_type` is stored and enforced with a composite FK `(service_bay_id, vehicle_type) → service_bay(id, vehicle_type)` → the DB itself rejects a truck in a private-car bay.
- Availability and cache key are scoped per pool: `avail:{dealershipId}:{date}:v{ver}:{serviceTypeId}:{vehicleType}`. Version key stays per dealership + date (one bump invalidates all pools — simple, and bookings are rare compared to reads).

```
Request: EV, battery check (60 min), 10:00–11:00, District 7
Pool EV:          Bay E1 ✗ busy   Bay E2 ✓ free
Pool PRIVATE_CAR: Bay P1 ✓ free   ← ignored (wrong pool)
Tech A: skill ✓, EV cert ✗        ✗ not qualified
Tech C: skill ✓, EV cert ✓, free  ✓
→ Bay E2 + Tech C
```

**Rejected / future:**
- Bay supports many vehicle types (`service_bay_vehicle_type` M:N, e.g. a truck bay also taking cars) — more flexible but needs a selection strategy to avoid wasting scarce truck bays on cars. Can be added later without breaking the API.
- Duration per (service type, vehicle type) — e.g. truck oil change longer. Future: `service_type_duration(service_type_id, vehicle_type, minutes)` with fallback to the default.

## Open Decisions
- [ ] Resource selection strategy (how technician / bay are picked)
- [ ] Data flow
