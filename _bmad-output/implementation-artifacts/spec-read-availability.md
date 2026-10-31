---
title: 'Read availability API (dealer + service type + vehicle type, from/to)'
type: 'feature'
created: '2026-10-31'
status: 'in-progress'
baseline_commit: '77d1b3491b5731c78838bbf0b60d9d8fa426883a'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/docs/detail-level-design.md'
  - '{project-root}/ROADMAP.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Customers cannot see when a dealership can take their job: there is no read endpoint for free slots, so every booking is a guess.

**Approach:** Add `GET /api/v1/availability?dealershipId=&serviceTypeId=&vehicleTypeId=&from=&to=`. One query is scoped to exactly one (dealership, service type, vehicle type) triple; it returns, per local date in `[from, to]`, the start/end windows where at least one active bay that takes the vehicle type AND one active technician with the skill and certification are both free for the whole service duration (DLD §8). Ship a Postman collection + environment so devs can import and test by hand against docker compose + seed data.

## Boundaries & Constraints

**Always:**
- All three ids and `from`/`to` are required; unknown dealership / service type / vehicle type → 404 ProblemDetail; bad or missing params, `to < from`, range > 14 days → 400 ProblemDetail.
- Dates and business hours are in the dealership's IANA time zone; a slot belongs to the local date it starts on. DST must be handled by `java.time` zone rules, never by fixed offsets.
- Slot window is half-open `[start, start + duration)`; it must lie fully inside `[open_time, close_time)` local (`24:00` = end of day). Overlap rule: `existing.start < new.end AND new.start < existing.end`, ignoring `CANCELLED`.
- Candidate starts: every 15 min from local `open_time`. Slots starting before "now" are dropped.
- A vehicle type that no bay or no technician at the dealership takes → 200 with empty slot lists (not an error on a read).
- **Decision (from/to):** `from` and `to` are ISO local dates (`2026-11-02`), inclusive, interpreted in the dealership's time zone.
- **Decision (cache):** DB-only read in this story; Redis cache-aside (D1 read path) is a follow-up story.
- Response: `{dealershipId, serviceTypeId, vehicleTypeId, timezone, durationMinutes, days:[{date, slots:[{start, end}]}]}`; `start`/`end` are ISO-8601 with the dealership's offset. Every date in range appears, even with no slots.

**Never:**
- No booking / write endpoints, no outbox changes, no Flyway migration.
- No rate limiting (separate concern, D1).
- Do not expose bay or technician ids in the response.
- No Redis cache yet (follow-up story).
- Do not change existing schema, seed data, or `RequestIdFilter`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy path | hub dealer, OIL_CHANGE (60m), GASOLINE_CAR, 1 day | slots every 15 min where a fitting bay+tech pair is free | N/A |
| Back-to-back | existing job 09:00–10:00 on the only pair | 10:00 start offered, 09:15 not | N/A |
| Cancelled overlap | only CANCELLED job in window | slot offered | N/A |
| Service longer than hours | 10h service at 08–17 dealer | empty slots | N/A |
| Open 24h | close `24:00`, 60m service | last slot 23:00–24:00 | N/A |
| DST day | New York, 2026-11-01 | local starts follow wall clock; offsets change −04:00 → −05:00 | N/A |
| Type not serviceable | HYDROGEN_CAR | 200, every day empty | N/A |
| Unknown id | random UUID dealershipId | — | 404 ProblemDetail |
| Bad range | `to` before `from`, or 15 days | — | 400 ProblemDetail |
| Missing / malformed param | no `vehicleTypeId`, `from=abc` | — | 400 ProblemDetail |

</frozen-after-approval>

## Code Map

- `server-api/src/main/resources/db/migration/V2__domain_schema.sql` -- tables to read: `dealerships(timezone, open_time, close_time)`, `service_types(duration_minutes)`, `vehicle_types`, `service_bays(active)` + `service_bay_vehicle_types`, `technicians(active)` + `technician_skills` + `technician_vehicle_types`, `appointments(start_time, end_time, status, technician_id, service_bay_id)`. Read only; do not change.
- `server-api/src/main/java/com/keyloop/scheduler/api/domain/AppointmentStatus.java` -- use `CANCELLED.name()` in the appointment query.
- `server-api/src/main/java/com/keyloop/scheduler/api/web/PingController.java` -- controller style: `@RestController @RequestMapping("/api/v1")`, short Javadoc citing the spec.
- `server-api/src/test/java/.../web/PingControllerTest.java` -- `@WebMvcTest` + MockMvc test style (Boot 4 package `org.springframework.boot.webmvc.test.autoconfigure`).
- `server-api/src/main/resources/application.yml` -- `spring.mvc.problemdetails.enabled: true` already on; reuse for 400s.
- `seeding/sql/*.sql` -- edge-case data (hub, New York DST, 24h, kiosk, HYDROGEN_CAR) used for manual Postman checks; seed "now" anchor is 2026-10-31 09:00 UTC.
- Data access: no JPA entities exist; use Spring `JdbcClient` for these read queries (avoids adding entities that `ddl-auto: validate` would check).

## Tasks & Acceptance

**Execution:**
- [x] `server-api/src/main/java/com/keyloop/scheduler/api/availability/AvailabilityRepository.java` -- JdbcClient queries: dealership, service type, vehicle-type existence; fitting active bay ids; qualified active technician ids; non-cancelled appointments of those resources overlapping the UTC span of the range -- one place for SQL.
- [x] `.../availability/SlotCalculator.java` -- pure function: hours + zone + duration + bay/tech busy intervals + now → slots per local date -- unit-testable without DB.
- [x] `.../availability/AvailabilityService.java` -- validate range, 404 on unknown ids, call repository + calculator -- orchestration.
- [x] `.../availability/AvailabilityController.java` + response records -- `GET /api/v1/availability`, `@RequestParam` UUID / date binding -- API surface.
- [x] `.../availability/NotFoundException.java` + `.../web/ApiExceptionHandler.java` -- map not-found → 404, range / binding errors → 400 ProblemDetail.
- [x] `server-api/src/test/java/.../availability/SlotCalculatorTest.java` -- cover every I/O matrix row that is pure logic (back-to-back, cancelled, too long, 24h, DST, now cut-off).
- [x] `server-api/src/test/java/.../availability/AvailabilityControllerTest.java` -- `@WebMvcTest` with mocked service: 200 shape, 400s, 404.
- [x] `postman/keyloop-scheduler.postman_collection.json` + `postman/local.postman_environment.json` + `postman/README.md` -- v2.1 collection: ping, happy path, DST, 24h, hydrogen empty, 404, 400s, each with test scripts; env holds `baseUrl` and seed ids.
- [x] `README.md` -- short "Manual API testing (Postman)" section.

**Acceptance Criteria:**
- Given the compose stack is up and seeded, when the Postman collection runs (Collection Runner or `newman`), then every request's tests pass.
- Given `./gradlew :server-api:test`, then all tests pass.

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Design Notes

Busy check per candidate window `w`: a free bay exists if any fitting bay has no busy interval overlapping `w`; same for technicians. Bays and technicians are checked independently — any free bay plus any free tech is a valid pair, because the DB fit FKs are per resource, not per pair. Load appointments once for the whole range (UTC span from local `from` 00:00 to local `to`+1 00:00), group by resource id in memory.

## Verification

**Commands:**
- `./gradlew :server-api:test` -- expected: BUILD SUCCESSFUL
- `docker compose up -d --build --wait && seeding/seed.sh` -- expected: healthy stack, seed verify passes
- `npx newman run postman/keyloop-scheduler.postman_collection.json -e postman/local.postman_environment.json` -- expected: 0 failures
