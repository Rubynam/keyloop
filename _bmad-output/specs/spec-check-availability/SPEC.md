---
id: SPEC-check-availability
companions:
  - edge-cases.md
  - brownfield.md
sources:
  - ../../implementation-artifacts/spec-read-availability.md
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# Check Availability — `GET /api/v1/availability`

## Why

Pain: customers, and staff booking for them, cannot see which times a dealership has free. A booking needs a free time where a service bay fits their vehicle type and a qualified technician is free for the whole service time. Without an availability read API they guess a time, send a booking request, and are often rejected with `409 Conflict`. This slot list is the precondition for booking and the hot read path that `spec-availability-ops` later protects with cache and rate limiting.

## Capabilities

- **CAP-1**
  - **intent:** A client can list free slots for one (dealership, service type, vehicle type) over a local date range `[from, to]`.
  - **success:** Each local date lists `[start, end)` windows where ≥1 active fitting bay AND ≥1 active qualified technician (skill for the service type + certified for the vehicle type) are free for the whole duration; every date in range appears, even with no slots.
- **CAP-2**
  - **intent:** Slots follow the dealership's local wall clock.
  - **success:** New York on 2026-11-01 returns offsets switching `-04:00` → `-05:00`; a dealership closing at `24:00` with a 60 min service yields a last slot 23:00–24:00.
- **CAP-3**
  - **intent:** A client sending a bad request learns why.
  - **success:** Unknown dealership / service type / vehicle type → 404; missing or malformed param, `to < from`, range > 14 days → 400; all as RFC 7807 `ProblemDetail`.
- **CAP-4**
  - **intent:** A vehicle type the dealership cannot service is an answer, not an error.
  - **success:** `HYDROGEN_CAR` → 200 with every date's slot list empty.
- **CAP-6**
  - **intent:** Returned slots are really bookable, proven against the real database.
  - **success:** A Spring integration test on the `IntegrationTest` base gets a slot from `GET /availability`, inserts an appointment in it on a fitting bay + qualified technician, and PostgreSQL accepts it; re-querying no longer offers that slot or any overlapping start when that pair was the only one.

## Constraints

- Windows are half-open `[start, end)`; overlap = `existing.start < new.end AND new.start < existing.end`; `CANCELLED` appointments are ignored.
- A window lies fully inside local `[open_time, close_time)`; `24:00` = end of day.
- Candidate starts every 15 min from local `open_time`; starts before "now" are dropped.
- `from` / `to` are ISO local dates, inclusive, in the dealership's IANA zone; a slot belongs to the date it starts on; zone math uses `java.time` rules, never fixed offsets.
- At most 14 days per request (D1 abuse bound).
- Bay and technician are checked independently: any free fitting bay plus any free qualified technician is valid, because the DB fit FKs are per resource, not per pair.
- The response never exposes bay or technician ids; resource choice belongs to booking.
- Read-only: no schema or seed change; `JdbcClient` queries, no JPA entities (`ddl-auto: validate`).
- Response shape is fixed — `spec-availability-ops` caches this JSON: `{dealershipId, serviceTypeId, vehicleTypeId, timezone, durationMinutes, days:[{date, slots:[{start, end}]}]}`, `start` / `end` ISO-8601 with the dealership's offset.
- Correctness tests run on real PostgreSQL 18 (Testcontainers, `IntegrationTest` base), not mocks; the exclusion constraint is the referee.

## Non-goals

- Redis cache, rate limiting (429), relay version-key bump, booking (bay, technician) candidate selection — `spec-availability-ops`.
- Write endpoints (book, cancel, reschedule) — booking spec.
- Buffer time between consecutive appointments (ROADMAP 1.4, open).
- Duration per (service type, vehicle type) (ROADMAP D2, future).
- A single-slot check API ("is 10:00 on 5 Nov possible?").
- Staff admin tool or admin-role login to check schedules; the endpoint has no authentication (deferred, `implementation-artifacts/deferred-work.md`).

## Success signal

- `./gradlew :server-api:test` passes, including the CAP-6 integration test that books into a returned slot on Testcontainers PostgreSQL. The CAP-6 test is a `@SpringBootTest` on `support/IntegrationTest` with its own fixture rows inserted via `JdbcClient`, never the shared seed data.
