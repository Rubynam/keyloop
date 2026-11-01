# Spec07 — Book an Appointment (`POST /api/v1/appointments`)

> Keyloop Technical Assessment · Scenario A: The Unified Service Scheduler
> Roadmap: **Phase 3 — Core Features (booking)** · Builds on [Spec03](Spec03.md), [Spec04 §6](Spec04.md#6-booking-retry--decided-behaviour-input-for-the-booking-spec), [Spec05](Spec05.md) · Story: [`story-book-appointment.md`](../_bmad-output/implementation-artifacts/story-book-appointment.md)
> Status: 📝 Draft · Owner: Dai Nam

---

## 1. Goal

Let a customer **book** a service for their vehicle at a time they pick. Today they can only see free slots (`GET /availability`). When Spec07 is done:

1. `POST /api/v1/appointments` keeps one free **service bay** and one qualified **technician** for the window `[startTime, startTime + duration)`, and answers `201 Created`.
2. **No double booking**, even under heavy parallel load. The PostgreSQL exclusion constraints (Spec03, D1) are the only judge; the Java code only picks good candidates.
3. The appointment and its `AppointmentBooked` outbox event are saved **in one commit** (Spec04 §4), so the availability cache is invalidated reliably.
4. A retried request with the same `Idempotency-Key` never creates a second appointment.
5. Every failure has a clear status and an RFC 7807 body (§6), written only by `ApiExceptionHandler`.

```
Client ─► nginx ─► server-api  POST /api/v1/appointments  (Idempotency-Key)
                      │
                      ├─ validate (edge + service)        ─► 400 / 404 / 422
                      ├─ pick pairs (SQL, no cache)       ─► 422 / 409
                      └─ 1 tx per pair, max 3 tries
                           INSERT appointment + outbox ─► COMMIT ─► 201
                                                              │
                     scheduler-batch OutboxRelay ◄────────────┘  INCR avail:ver:{dealer}:{date}
```

---

## 2. Scope

### In scope
- The booking endpoint, its validation, candidate selection and the transaction loop (§4–§7).
- Slot step moves from a constant to config, shared by availability and booking (§5.3, decision D4).
- Package move of the existing `booking/` code into the `appointment` feature (§8, decision D6).
- New exceptions and handlers in `ApiExceptionHandler` (§6).
- Unit, slice, integration and concurrency tests (§10).
- Postman: happy path + one request per error case.
- Docs **after approval**: DLD diagrams 03 (add `422`, `503`) and 05 / 11 if the contract changes; CLAUDE.md layout tree (§8).

### Out of scope
- `GET /appointments/{id}`, cancel, reschedule (next specs).
- Nested path `/dealerships/{dealershipId}/appointments` (follow-up, together with Spec05 Q1 — decision D1).
- Rate limiting (`429`).
- Cache writes from the API: the outbox relay already bumps `avail:ver:*` (Spec04 §4).

---

## 3. Decisions

From the story review (G1–G9) and from reading the current code.

| # | Question | Decision | Why |
|---|---|---|---|
| D1 (G1) | Flat `/appointments` or nested under the dealership? | **Flat now.** Move to `/dealerships/{dealershipId}/appointments` in a follow-up, with availability (Spec05 Q1). | One path change for both endpoints, one Postman / nginx / Locust update. |
| D2 (G2) | Vehicle already booked at that time? | **`422`**, checked in the service **before** any pair. `ex_appointments_vehicle` (`23P01`) maps to the same `422` as a safety net. | Trying 3 pairs would fail 3 times the same way and waste 3 transactions. It is a rule about the customer, not a lack of resources. |
| D3 (G3) | Same `Idempotency-Key`, different body? | **`422`.** Compare the 5 request fields with the stored row. No migration. | Returning the old booking with `201` hides a client bug. |
| D4 (G4) | Any minute, or only slot-grid starts? | **Grid only.** Off-grid start → `422`. Step is config `scheduler.availability.slot-step` (default `15m`), **one key for availability and booking**. | What the customer sees is exactly what can be booked; no bay time lost to odd starts (§5.3). |
| D5 (G5) | `@Future` uses the JVM clock, not the `Clock` bean | Register a Hibernate Validator `ClockProvider` backed by the `Clock` bean (`ClockConfig`). | Keeps the check at the edge and makes tests with `MutableClock` stable. |
| D6 | Story says "`CandidateSelector` becomes a pure helper in `service/appointment/impl`". It is not pure: it reads two repositories. | **Keep it an interface + impl**, move `booking/` → `appointment/` in every layer. | CLAUDE.md §1: helpers with I/O are services. One feature name (`appointment`) for one resource. |
| D7 (G9) | Outbox type and aggregate as plain strings? | Constants in `OutboxEventTypes` (shared, layer root). Payload tested against `docs/contracts/outbox/AppointmentBooked.json`. | The worker reads the same contract (Spec04 §4.4). |
| D8 (G8) | 5–7 reads per booking in `CandidateSelector` | **Accept.** All small and indexed. Merge into one SQL only if the Spec06 load test shows a need. | Rule of Three; no evidence yet. |

---

## 4. API contract

```
POST /api/v1/appointments
Idempotency-Key: 6f1c2a0e-...            (required, 1..128 chars)
Content-Type: application/json

{
  "customerId":    "0194...-c1",
  "vehicleId":     "0194...-v1",
  "dealershipId":  "0194...-d1",
  "serviceTypeId": "0194...-s1",
  "startTime":     "2026-11-02T09:00:00+07:00"
}
```

```
201 Created
Location: /api/v1/appointments/01a0...-a1
{
  "id":            "01a0...-a1",
  "status":        "CONFIRMED",
  "dealershipId":  "0194...-d1",
  "serviceBayId":  "0194...-b2",
  "technicianId":  "0194...-t2",
  "serviceTypeId": "0194...-s1",
  "vehicleId":     "0194...-v1",
  "startTime":     "2026-11-02T02:00:00Z",
  "endTime":       "2026-11-02T03:00:00Z"
}
```

- The client **never** sends `vehicleTypeId`, `endTime`, `serviceBayId` or `technicianId`.
- `vehicleTypeId` = `vehicles.vehicle_type_id`. `endTime` = `startTime + service_types.duration_minutes`.
- Times in the response are UTC (`Instant`). The `Location` path is built from `ApiPaths`, not a literal.
- `startTime` without an offset (`2026-11-02T09:00`) fails JSON parsing → `400`.

---

## 5. Validation

### 5.1 Two levels

```
                 ┌──────────── controller edge (Bean Validation) ────────────┐
request ──►      │ body:   5 fields @NotNull, startTime @Future (Clock, D5)  │ ──► 400
                 │ header: Idempotency-Key @NotBlank @Size(max = 128)        │
                 └───────────────────────────────────────────────────────────┘
                                         │ ok
                 ┌──────────── service (reads only) ─────────────────────────┐
                 │ 0. key already used? same body → 201 old / other → 422 D3 │
                 │ 1. dealership, service type, vehicle, customer exist  404 │
                 │ 2. vehicle belongs to customer                        400 │
                 │ 3. start on the slot grid (D4)                        422 │
                 │ 4. [start, end) inside business hours, local time     422 │
                 │ 5. vehicle has no overlapping appointment (D2)        422 │
                 └───────────────────────────────────────────────────────────┘
```

Order matters: the cheap idempotency lookup runs first, so a retried request never re-validates against data that has changed since the first call.

### 5.2 Edge rules
- Package is **`jakarta.validation`** (Spring Boot 3+; `javax.validation` is gone).
- `BookAppointmentRequest` is a `record` with constraints on its components; the method takes `@Valid @RequestBody`.
- The header is a parameter: `@RequestHeader(HeaderNames.IDEMPOTENCY_KEY) @NotBlank @Size(max = AppointmentConstants.IDEMPOTENCY_KEY_MAX) String key`. The controller class has `@Validated`.

### 5.3 Slot grid (D4)

All checks use the dealership's **local** time (`dealerships.timezone`), so they hold on a DST day.

```
local start − open_time must be a multiple of slot-step
  open 08:00, step 15m
  09:00 → 60 min → 60 % 15 = 0   ok
  09:07 → 67 min → 67 % 15 = 7   422 "start must be on a slot"
```

```
application.yml ──► AvailabilityProperties.slotStep (Duration, @ConfigurationProperties, @Validated)
                          │
            ┌─────────────┴──────────────┐
            ▼                            ▼
  SlotCalculator (builds slots)   AppointmentServiceImpl (checks grid)
```

- Remove `AvailabilityConstants.STEP_MINUTES`; `SlotCalculator` takes the step as a parameter and stays a pure helper.
- Startup check: `slot-step > 0` and `24h % slot-step == 0`. A bad value fails at boot, not on the first request.
- After a step change, cached slot lists stay old for at most the cache TTL (30–60 s). Accepted.

---

## 6. Errors

All bodies are RFC 7807 `ProblemDetail`, built only in `ApiExceptionHandler`. Texts live in `ErrorMessages` / `AppointmentConstants`.

| Case | Exception | Status | `detail` |
|---|---|---|---|
| Bad body / header | `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `HttpMessageNotReadableException` | 400 | "Invalid request" + `errors[]` |
| Vehicle of another customer | `VehicleOwnerMismatchException` | 400 | "The vehicle does not belong to this customer." |
| Unknown id | `NotFoundException` (exists) | 404 | "`<Entity>` `<id>` not found" |
| All pairs failed / none free | `BookingConflictException` | 409 | Spec04 §6 text |
| Vehicle already booked | `VehicleBusyException` | 422 | "The vehicle already has an appointment at this time." |
| Key reused, other body | `IdempotencyKeyMismatchException` | 422 | "This Idempotency-Key was already used for a different request." |
| No bay / tech can serve | `NotServiceableException` | 422 | "No bay or technician at this dealership can service this vehicle." |
| Outside business hours | `OutsideBusinessHoursException` | 422 | "The time is outside the dealership's business hours." |
| Off the slot grid | `OffSlotGridException` | 422 | "The start time must be on a %d-minute slot." |
| DB down | `DataAccessException` (exists) | 503 | existing text |

400 body with field errors:

```json
{ "title": "Bad Request", "status": 400, "detail": "Invalid request",
  "errors": [ { "field": "startTime", "message": "must be a future date" } ] }
```

The 4xx exceptions are logged at `WARN`, not `ERROR` (same rule as commit `3a6c253`).

---

## 7. Booking transaction

### 7.1 Candidate selection (existing, moved)

`CandidateSelector.select(dealershipId, serviceTypeId, vehicleTypeId, start)` returns up to 3 ordered `(bay, technician)` pairs from **PostgreSQL only** ([`candidate-selection.md`](../_bmad-output/specs/spec-availability-ops/candidate-selection.md)). The read is **not locked**: a stale read costs one retry, never a double booking.

```
 fitting bays           qualified technicians
 active, takes V1       active, skill S1, certified V1
        │                        │
        └── busy in window? ─────┘   tstzrange && , status <> 'CANCELLED'
        │                        │
 fewer vehicle types first   fewer jobs that local day first
        └──────── pairs ─────────┘   (B2,T2) (B1,T1) (B2,T1)

NOT_SERVICEABLE → 422      NONE_FREE → 409      PAIRS → §7.2
```

### 7.2 One transaction per pair

```
for pair in pairs (max = scheduler.booking.max-pairs, no wait):
   BEGIN
     INSERT appointments (... status = CONFIRMED, idempotency_key = :key) RETURNING id
     INSERT outbox_events (aggregate_type = 'Appointment', aggregate_id = :id,
                           event_type = 'AppointmentBooked',
                           payload = {appointmentId, dealershipId, dates[local]})
   COMMIT ───────────────────────────────────────────────────► 201 + Location
   on error → ROLLBACK, then:
     SlotTakenException(BAY | TECHNICIAN)   23P01 ex_appointments_bay / _technician ─► next pair
     ResourceGoneException                  23503 bay / tech FK (deactivated)       ─► next pair
     SlotTakenException(VEHICLE)            23P01 ex_appointments_vehicle           ─► stop, 422 (D2)
     DuplicateIdempotencyKeyException       23505 idempotency_key (same-key race)   ─► read stored row → 201 / 422 (D3)
     other DataAccessException                                                      ─► 503
after the last pair fails ──────────────────────────────────────────────────────────► 409
```

- The service never sees SQL or SQLSTATE. `JdbcAppointmentRepository` maps SQLSTATE + constraint name to the small domain exceptions above.
- The transaction is run through an injected `TransactionOperations` (an interface; precedent: Spec05 RF-06). Not `@Transactional` on the loop, because each pair needs its **own** transaction.
- Outbox `dates` use the dealership's **local** date (Spec03 §5). A window that crosses local midnight lists both dates.
- One log line per try: `pair`, `outcome`. Never names, phone numbers or other customer data.

---

## 8. Files (Spec05 layout)

```
server-api  (com.keyloop.scheduler.api)
├── controller/appointment/   AppointmentController
├── service/appointment/      AppointmentService, CandidateSelector          (interfaces)
│   └── impl/                 AppointmentServiceImpl, CandidateSelectorImpl
├── repository/appointment/   AppointmentRepository, CandidateRepository, OutboxRepository
│   └── impl/                 JdbcAppointmentRepository, JdbcCandidateRepository, JdbcOutboxRepository
├── model/appointment/        CandidateResult, NewAppointment, StoredAppointment
├── dto/appointment/          BookAppointmentRequest, AppointmentResponse
├── exception/appointment/    BookingConflictException, VehicleBusyException, NotServiceableException,
│                             IdempotencyKeyMismatchException, OutsideBusinessHoursException,
│                             OffSlotGridException, VehicleOwnerMismatchException,
│                             SlotTakenException, ResourceGoneException, DuplicateIdempotencyKeyException
├── constant/                 ApiPaths.APPOINTMENTS, HeaderNames.IDEMPOTENCY_KEY, OutboxEventTypes
│   └── appointment/          AppointmentConstants   (replaces constant/booking/BookingConstants)
└── config/
    ├── availability/         AvailabilityProperties (slot-step)
    └── appointment/          BookingProperties (max-pairs), ValidationClockConfig (D5)
```

Moves: every `*/booking/*` class → `*/appointment/*` (same names). `CandidateSelectorIT` moves with them and stays green. CLAUDE.md §1 tree is updated in the same change.

---

## 9. Configuration (`server-api/application.yml`)

| Key | Default | Meaning |
|---|---|---|
| `scheduler.availability.slot-step` | `15m` | Slot grid for availability **and** booking (D4) |
| `scheduler.booking.max-pairs` | `3` | Pairs tried before `409` (Spec04 §6) |

---

## 10. Requirements

### 10.1 API and validation

| ID | Requirement |
|---|---|
| BK-01 | `POST {API_V1}{APPOINTMENTS}` returns `201`, a `Location` header and `AppointmentResponse`. The controller makes **one** service call. |
| BK-02 | Edge validation as §5.2. Missing / null / bad UUID / past `startTime` / no offset → `400` with the field in `errors`. |
| BK-03 | Missing, blank or 129-char `Idempotency-Key` → `400`. |
| BK-04 | `@Future` reads the `Clock` bean through a `ClockProvider` (D5). |
| BK-05 | Service checks run in the order of §5.1 and answer the statuses there. |
| BK-06 | `slot-step` is read from `AvailabilityProperties`; `STEP_MINUTES` is removed; bad values fail at startup. |

### 10.2 Transaction

| ID | Requirement |
|---|---|
| BK-07 | One transaction per pair, at most `max-pairs` tries, no wait (Spec04 §6). |
| BK-08 | Appointment row and outbox row commit together or not at all. |
| BK-09 | SQLSTATE is mapped only in `repository/**/impl` (§7.2 table). |
| BK-10 | Outbox row: type `OutboxEventTypes.APPOINTMENT_BOOKED`, payload matches `docs/contracts/outbox/AppointmentBooked.json`, local dates. |
| BK-11 | Same key + same body → `201` with the stored appointment, sequential **and** parallel. Same key + other body → `422`. |

### 10.3 Architecture

| ID | Requirement |
|---|---|
| BK-12 | `booking/` packages are gone; `ArchitectureTest` (ARC-01…08) passes with the new `appointment/` packages. |
| BK-13 | No SQL, `JdbcClient` or SQLSTATE outside `repository/**/impl`. No literals outside constants / yml (CLAUDE.md §3). |
| BK-14 | Two-part Javadoc on every public type and method (CLAUDE.md §4). |

---

## 11. Implementation Steps

1. **Move** `booking/` → `appointment/` in every layer; `BookingConstants` → `AppointmentConstants`. Build + `CandidateSelectorIT` green. *(no behaviour change, own commit)*
2. **Slot step to config**: `AvailabilityProperties`, `SlotCalculator` takes the step, startup check, availability tests green.
3. **Contract + validation**: DTOs, controller, `ClockProvider`, 400 handlers, `@WebMvcTest`.
4. **Service checks**: repositories for the reads in §5.1, new exceptions + handlers.
5. **Transaction loop**: `AppointmentRepository`, `OutboxRepository`, SQLSTATE mapping, `TransactionOperations`, idempotency race.
6. **Tests**: IT with Testcontainers for §12, concurrency IT.
7. **Postman** + switch on the Spec06 booking cases.
8. **Docs** (after approval): DLD 03 (`422`, `503`), 05, 11; CLAUDE.md tree; story status.

---

## 12. Acceptance Criteria

### Validation
- [ ] AC1 Missing field, null id, bad UUID text, past `startTime`, no offset → `400` with the field in `errors`. *(`@WebMvcTest`, no DB)*
- [ ] AC2 Missing or 129-char `Idempotency-Key` → `400`. *(`@WebMvcTest`)*
- [ ] AC3 Unknown dealership / service type / vehicle / customer → `404` naming the entity.
- [ ] AC4 Vehicle of another customer → `400`.
- [ ] AC5 Window outside business hours (local time) → `422`.
- [ ] AC6 Vehicle already has an overlapping non-cancelled appointment → `422`; no pair is tried.
- [ ] AC7 09:07 local (open 08:00, step 15m) → `422`; 09:00 → passes. Also true on a DST day.
- [ ] AC8 With `slot-step: 30m`, 09:15 → `422`, and `GET /availability` shows only :00 / :30.
- [ ] AC9 `@Future` follows `MutableClock` in tests.

### Candidates
- [ ] AC10 No fitting bay or no qualified technician → `422` "not serviceable".
- [ ] AC11 All bays or all technicians busy → `409` with the Spec04 §6 text.
- [ ] AC12 A `CANCELLED` appointment does not block; back-to-back `[08:00, 09:00)` does not block `[09:00, 10:00)`.
- [ ] AC13 Same DB state → same pairs, same order (`CandidateSelectorIT`).

### Transaction
- [ ] AC14 Happy path: `201` + `Location`; one `appointments` row and one `AppointmentBooked` outbox row in one commit.
- [ ] AC15 First pair taken by a concurrent insert → second pair booked → `201`.
- [ ] AC16 All pairs fail → `409`; no rows left behind.
- [ ] AC17 Outbox insert fails → appointment rolled back too.
- [ ] AC18 Same key twice, one after the other **and** in parallel → both `201`, same `id`, one row. Other body → `422`.
- [ ] AC19 Concurrency IT: 20 parallel requests, one window, 2 free pairs → exactly 2 × `201`, 18 × `409`, no overlap in the table.
- [ ] AC20 `./gradlew build` passes, including `ArchitectureTest`.

---

## 13. Trade-offs & Open Questions

- **No lock on the candidate read.** Under heavy contention most requests may burn 3 transactions before `409`. Accepted: each try is short, and the constraint keeps the data correct. Revisit with Spec06 `race` / `stress` numbers.
- **Idempotency key lives on the appointment row** (`appointments.idempotency_key UNIQUE`), not in a separate table. Simple, no migration; but a cancelled appointment still "owns" its key. Fine until cancel exists.
- **Slot step change** leaves cached slots stale for ≤ TTL (§5.3). Accepted.
- **Pre-checks race with writes.** Checks in §5.1 can pass and then the row changes; the constraints and FKs catch it (§7.2), so the cost is only a less precise message in a rare case.
- [ ] G7: upper limit on how far ahead a customer can book (`scheduler.booking.max-days-ahead` → `422`)? Default proposal: same limit as the availability max range.
- [ ] Should `201` for a replayed key carry a header (e.g. `Idempotent-Replayed: true`) so clients can tell?
