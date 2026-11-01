---
title: 'Spec07 — Book an appointment (POST /api/v1/appointments)'
type: 'feature'
created: '2026-11-01'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '251a8b05346efce5238442510891c865838754d3'
context: ['{project-root}/planing/Spec07.md', '{project-root}/CLAUDE.md', '{project-root}/planing/Spec04.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Customers can see free slots (`GET /availability`) but cannot book one. The candidate-selection code exists but has no caller, and the slot step is a hard-coded constant.

**Approach:** Implement `planing/Spec07.md` §4–§9 as written (decisions D1–D8): flat `POST /api/v1/appointments` with `Idempotency-Key`, edge + service validation, unlocked candidate read, one transaction per pair (appointment + `AppointmentBooked` outbox row) through `TransactionOperations`, PostgreSQL exclusion constraints as the only double-booking judge. Move `booking/` → `appointment/` first; move slot step to `scheduler.availability.slot-step`.

## Boundaries & Constraints

**Always:** CLAUDE.md §1–§4 (interfaces, constructor injection, constants/yml, two-part Javadoc). SQL and SQLSTATE/constraint-name mapping only in `repository/**/impl`. Every error body is built in `ApiExceptionHandler`; new 4xx exceptions implement `ClientError` (WARN logging). All grid / hours / outbox-date checks use the dealership's local time. Response times are UTC `Instant`. Package move is its own commit with no behaviour change.

**Decisions (human, 2026-11-01):**
- G7: no booking horizon limit now; any future grid start inside business hours is bookable.
- Replay: a replayed `201` (same key, same body — sequential or parallel race) carries header `Idempotent-Replayed: true` (`HeaderNames.IDEMPOTENT_REPLAYED`); a first booking does not. The service tells the controller via the result (e.g. a `replayed` flag on the service result), not via the response DTO body.
- Spec kept whole (~2,300 tokens); commits follow Spec07 §11.

**Never:** Flyway migrations; locks on the candidate read; `@Transactional` around the pair loop; cache writes from the API; GET/cancel/reschedule endpoints; nested dealership path; rate limiting; logging customer data; editing CLAUDE.md or DLD diagrams beyond Spec07 §8 layout tree (DLD docs only after human approval).

## I/O & Edge-Case Matrix

Full status/message table: `planing/Spec07.md` §5.1, §6, §7.2. Key rows:

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy path | valid body, grid start, free pair | `201` + `Location` from `ApiPaths`; 1 appointment + 1 outbox row, one commit | N/A |
| Bad edge input | missing field / bad UUID / past start / no offset / bad key | `400` with `errors[]` (`field`, `message`) | handler, no DB |
| Unknown id | dealership, service type, vehicle or customer | `404` naming the entity | `NotFoundException` |
| Wrong owner | vehicle of another customer | `400` | `VehicleOwnerMismatchException` |
| Off grid / outside hours / vehicle busy | e.g. 09:07 with open 08:00, step 15m | `422`, no pair tried | dedicated exceptions |
| No bay/tech can serve | outcome `NOT_SERVICEABLE` | `422` | `NotServiceableException` |
| All busy / all pairs fail | `NONE_FREE`, or every pair hits `23P01`/`23503` | `409` (Spec04 §6 text), no rows left | `BookingConflictException` |
| Replay | same key, same 5 fields (sequential or parallel) | `201` + `Idempotent-Replayed: true`, same `id`, one row | `23505` race → re-read |
| Key misuse | same key, other body | `422` | `IdempotencyKeyMismatchException` |
| DB down | any `DataAccessException` | `503` (existing) | existing handler |

</frozen-after-approval>

## Code Map

`M` = `server-api/src/main/java/com/keyloop/scheduler/api`, `T` = same under `src/test`.

- `M/{service,repository,model,constant}/booking/**` + `T/service/booking/impl/CandidateSelectorIT` → `appointment/` (`git mv`, same names). `BookingConstants` → `constant/appointment/AppointmentConstants`. Only caller today is the IT.
- `CandidateSelectorImpl(AvailabilityRepository, CandidateRepository)`; caps pairs with `BookingConstants.MAX_PAIRS=3` → replace with `BookingProperties.maxPairs` (prefix `scheduler.booking`). Keep `select(...)` signature and `CandidateResult` (`Outcome`, `Pair`, factories).
- `AvailabilityConstants.STEP_MINUTES` (l.26) used only in `SlotCalculator.compute` loop (l.60) → add step parameter; `AvailabilityServiceImpl` passes `AvailabilityProperties.slotStep`. Startup check: `> 0` and `24h % step == 0`.
- Config pattern: `config/availability/AvailabilityCacheProperties` record + `@EnableConfigurationProperties` on `AvailabilityConfig` (no scan). `application.yml` l.34-43 holds `scheduler.availability.cache.*`.
- Reuse `AvailabilityRepository.findDealership` (`Dealership(timezone, openMinute, closeMinute)`, close 1440 allowed) and `findServiceDurationMinutes`. New reads (vehicle with `customer_id` + `vehicle_type_id`, customer exists, vehicle overlap, appointment by idempotency key) go in `AppointmentRepository`.
- Schema (V2): `appointments` has `start_time`/`end_time` TIMESTAMPTZ, `vehicle_type_id`, `status` default `CONFIRMED`, `idempotency_key VARCHAR(128) UNIQUE` → constraint `appointments_idempotency_key_key`. Exclusions `ex_appointments_{bay,technician,vehicle}`; FKs `fk_appointments_bay_*`, `fk_appointments_tech_*` → `ResourceGoneException`; `fk_appointments_vehicle_owner`. `outbox_events(aggregate_type, aggregate_id VARCHAR(64), event_type, payload JSONB)`; insert form in `loadtest/simulator/simulate.py:76`.
- Outbox payload contract `docs/contracts/outbox/AppointmentBooked.json`: `{appointmentId, dealershipId, dates[yyyy-MM-dd local]}`; relay reads `dealershipId`, `dates`.
- `web/ApiExceptionHandler` extends `ResponseEntityExceptionHandler`, `problem()` helper l.50; today no `errors[]` → override `handleMethodArgumentNotValid`, `handleHandlerMethodValidationException`, `handleHttpMessageNotReadable`.
- Constants: `ApiPaths` (add `APPOINTMENTS`), `HeaderNames` (add `IDEMPOTENCY_KEY`, `IDEMPOTENT_REPLAYED`), `ErrorMessages` (add `ENTITY_CUSTOMER`, `ENTITY_VEHICLE`, `INVALID_REQUEST`); new shared `constant/OutboxEventTypes`.
- `config/ClockConfig` → `Clock.systemUTC()`; `T/support/MutableClock`. D5: `ValidationClockConfig` supplies `ClockProvider` via `LocalValidatorFactoryBean` customizer.
- WARN/ERROR via `ClientError` in `common-logging/.../MethodLoggingAspect.java:45` — do not change.
- `T/ArchitectureTest` ARC-01…08: `TransactionOperations` allowed in services; exceptions must sit in `exception..`; repository impl must not depend on service.
- Tests: `T/support/IntegrationTest` (Testcontainers PG + Redis), `Fixtures` (add `customer()`, `vehicle(customerId, vehicleTypeId)`), `@WebMvcTest` sample `T/controller/availability/AvailabilityControllerTest`. Jackson 3 (`tools.jackson`), validation starter present.
- Postman: `postman/keyloop-scheduler.postman_collection.json`. Spec06 cases `loadtest/input/30-booking.json`, `40-lifecycle.json`, `60-race.json` already target the endpoint; "switch on" = make them pass, no code flag.

## Tasks & Acceptance

**Execution:**
- [x] `booking/` → `appointment/` in every layer + IT -- move only, own commit -- Spec07 §11.1.
- [x] `config/availability/AvailabilityProperties`, `SlotCalculator`, `AvailabilityServiceImpl`, `application.yml` -- slot step to config, startup check, remove `STEP_MINUTES`; availability tests green.
- [x] `config/appointment/BookingProperties`, `CandidateSelectorImpl` -- `max-pairs` from config.
- [x] `dto/appointment/*`, `controller/appointment/AppointmentController`, `config/appointment/ValidationClockConfig`, constants, `ApiExceptionHandler` 400 overrides -- edge contract; `@WebMvcTest` for AC1/AC2/AC9.
- [x] `exception/appointment/*`, handler mappings -- Spec07 §6 table, all `ClientError` except internal-only signals.
- [x] `repository/appointment/{AppointmentRepository,OutboxRepository}` + Jdbc impls -- reads, inserts, SQLSTATE + constraint-name mapping.
- [x] `service/appointment/AppointmentService` + `impl/AppointmentServiceImpl` -- §5.1 check order, pair loop over `TransactionOperations`, idempotency race, one log line per try.
- [x] `T/support/Fixtures`, ITs -- Spec07 §12 AC3–AC19 incl. 20-thread concurrency IT; AC17 via a spied `OutboxRepository` that throws.
- [x] `postman/keyloop-scheduler.postman_collection.json` -- happy path + one request per error case.
- [x] `CLAUDE.md` §1 tree -- `booking/` → `appointment/` entries.

**Acceptance Criteria:** Spec07 §12 AC1–AC20 are the acceptance set. In addition:
- Given `slot-step: 0m` or `7m`, when server-api starts, then boot fails with a binding error.
- Given the change, when `rg -n 'booking' server-api/src/main/java` runs, then no package path matches.

## Implementation Notes

- **No class-level `@Validated` on `AppointmentController`** (Spec07 §5.2 asks for it). With it, Spring MVC skips its built-in method validation and the header checks run through the AOP proxy, which throws `ConstraintViolationException` (500 today). Without it, header and body errors arrive as `HandlerMethodValidationException` / `MethodArgumentNotValidException`, which is what Spec07 §6 maps. Tried both; documented in the controller Javadoc.
- **Deadlock (`40P01`) is a lost pair.** Under the 20-thread IT, parallel inserts wait on each other's uncommitted exclusion entries and PostgreSQL aborts one with `40P01`. Unmapped, that was a 503. `JdbcAppointmentRepository` now maps it to `SlotTakenException(PAIR)` → next pair. Cost: each deadlock waits `deadlock_timeout` (1 s default) before PostgreSQL breaks it.
- **Race fallbacks.** When a booking ends in `VehicleBusyException` or `BookingConflictException`, the service reads the key once more: a parallel call with the same key may have committed first, and the client gets that appointment back (replay). After every pair fails, a vehicle overlap check turns the 409 into 422 (D2) when a parallel booking of the same vehicle won.
- `org.postgresql:postgresql` moved from `runtimeOnly` to `implementation`: the repository reads `PSQLException.getServerErrorMessage().getConstraint()`; parsing localized message text would be fragile.
- Extra files beyond Spec07 §8: `config/appointment/AppointmentConfig` (binds `BookingProperties`), `model/appointment/VehicleRef`, `model/appointment/AppointmentBookedPayload`, `dto/appointment/BookingResult` (the `replayed` flag).
- Missing `Idempotency-Key` (`MissingRequestHeaderException`) also gets the `errors[]` shape.
- Spec06 load-test expectations changed to match Spec07: BK-01 allows 422, BK-03 / RC-03 409 → 422 (D2), BK-05 / BK-06 400 → 422 (§5.1). BK-01's 409 still expects a `traceId` body field, which the API does not emit (tracing is a separate story).
- `@Transactional` is not used; each pair runs in `TransactionOperations` (Boot's `TransactionTemplate`).
- Not done: DLD diagrams 03 / 05 / 11 (Spec07 §11.8, after human approval). Postman / load test not run against a live stack.

## Spec Change Log

## Review Triage Log

Pass 1 (2026-11-01). Layers: blind-hunter (B), edge-case-hunter (E), verification-gap (V).

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| B1 | BK-01 expects `traceId` on 409 | medium | Assertion existed at baseline `251a8b0` (`loadtest/expected/30-booking.json:29`); tracing is not this story | defer |
| B2/E3 | Key of a CANCELLED appointment replays as 201 | low | Real only once cancel exists; Spec07 §13 accepts "a cancelled appointment still owns its key" until then | reject |
| B3 | Idempotency key is global, not per customer | low | Spec07 §13 decision (UNIQUE on row, no migration); scoping needs a migration, which the intent forbids | reject |
| B4/V1 | 23503 bay/tech FK → next pair never tested | medium | No test reaches `ResourceGoneException`; a prefix typo would turn a bookable request into 503 | patch |
| V3 | `fk_appointments_vehicle_owner` on insert → 400 untested | low | ac4 stops at the service pre-check; mapping never runs in tests | patch (with V2 unit test) |
| V2 | 40P01 → `SlotTakenException(PAIR)` only hit by thread timing | medium | No deterministic test; removing the branch passes most CI runs | patch |
| B5 | Multi-date outbox payload untested | false | A window over local midnight cannot pass the hours check (close is the start date's close, max 24:00), so the branch is unreachable from booking | reject |
| B6/V4 | `max-pairs` binding / `@Positive` untested | low | Default 3 equals yml; a wrong prefix or `max-pairs: 0` would go unnoticed; fix is one small test | patch |
| B6b | AC13 / AC20 not tagged in ITs | false | AC13 = `CandidateSelectorIT` (moved, green); AC20 = `./gradlew build` | reject |
| B7 | Slot step not in availability cache key | low | Spec07 §5.3 accepts stale slots ≤ TTL after a step change | reject |
| B8 | Dealership / zone read twice per booking | low | Spec07 D8 accepts the extra small indexed reads until Spec06 shows a need | reject |
| B9 | `JdbcOutboxRepository` Javadoc has no Part 2 example | low | CLAUDE.md §4 requires two-part Javadoc on repository classes; direct fix | patch |
| B9b | `append(..., Object payload)` weakly typed | low | Only caller passes `AppointmentBookedPayload`; contract test (ac14 payload) guards it; fix adds API | reject |
| B10 | 40001 / 55P03 not mapped; no deadlock metric | false | Default READ COMMITTED never raises 40001 here; no `lock_timeout` set; metrics not in scope | reject |
| B11 | Postman kiosk 409 request pinned to 2026-11-04 | low | Real, but kiosk seed is date-pinned; CI covers 409 deterministically (ac11, ac16); Postman is a manual smoke | reject |
| B12a / E6 | `errors[].field` empty when path has only indexes (body is a JSON array) | low | `[]` body → joined names = ""; direct fix: fall back to `body` | patch |
| B12b | Unknown `status` text → `valueOf` 500 | low | Only the app writes `status`; no reachable path | reject |
| B12c | No test of header + body errors together | low | Each half is tested; combined path uses the same `ParameterErrors` loop | reject |
| V5 | Replay-after-loss and 409→422 re-check only hit by timing | low | Real, but a deterministic test needs injected interleaving; fix is far more than a direct correction | reject |
| E1 | 23503 on vehicle-type / dealership / service-type FK → 503 | low | Needs a vehicle type change or dealership delete mid-request; adds branches | reject |
| E2 | `switch` on null SQLSTATE → NPE | false | PostgreSQL always sends field `C` (SQLSTATE) in an ErrorResponse | reject |
| E4/E9 | Retry after `startTime` passed → 400, not replay | low | Spec07 §5.1 puts `@Future` at the edge by design; retries come seconds later | reject |
| E5 | Start before open: grid vs hours message differs | false | Both answer 422; an unaligned start before open is off-grid by D4 | reject |
| E7 | `ParameterErrors` global errors dropped | false | Request record has no class-level constraints | reject |
| E8 | `@RequestHeader(name=)` not read | false | Controller uses `value` (positional); no other caller | reject |
| E10 | No class-level `@Validated` (Spec07 §5.2) | low | Documented deviation; with it, bad keys gave 500; observable contract (400 + `errors[]`) matches §6 | reject |

## Verification

**Commands:**
- `./gradlew build` -- expected: all modules green, including Testcontainers ITs and `ArchitectureTest`.
- `./gradlew :server-api:test --tests '*Appointment*' --tests '*CandidateSelectorIT'` -- expected: green.
