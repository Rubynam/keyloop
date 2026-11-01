# Story — Book an appointment (`POST /api/v1/appointments`)

Status: ready for dev (architect review done, G1–G4 decided)
Owner: John (PM) · Date: 2026-11-01
Sources: DLD §5 (`05-seq-book-appointment`), DLD §11 (`11-error-handling`), Spec04 §6 (retry), `specs/spec-availability-ops/candidate-selection.md`, CLAUDE.md §1–§4

---

## 1. User story

**As a** customer of a dealership,
**I want to** book a service for my vehicle at a time I choose,
**so that** a free service bay and a qualified technician are kept for me, and nobody else can take that time.

Why now: customers can already see free slots (`GET /availability`), but they cannot book one. Booking is the core value of the scheduler.

---

## 2. The flow in one picture

```
Client
  │  POST /api/v1/appointments   (header: Idempotency-Key)
  ▼
AppointmentController ── @Valid ──► 400 if the body or header is bad      ◄── Task 1
  │ book(request, key)
  ▼
AppointmentService (interface) ─► AppointmentServiceImpl
  │
  ├─ 0. key already used?  ── same body ──► 201 + the same appointment
  │                          ── other body ─► 422 (G3)
  │
  ├─ 1. business checks    ── fail ──► 404 / 400 / 422                    ◄── Task 1
  │      (ids exist, vehicle belongs to customer, time in business hours,
  │       vehicle has no overlapping appointment)
  │
  ├─ 2. find candidates (SQL)                                             ◄── Task 2
  │      NOT_SERVICEABLE ──► 422
  │      NONE_FREE       ──► 409
  │      PAIRS: (B1,T1) (B2,T2) (B3,T3)
  │
  └─ 3. submit, one transaction per pair, max 3 tries, no wait            ◄── Task 3
         try (B1,T1) ── 23P01 / 23503 ──► rollback, next pair
         try (B2,T2) ── OK ──► INSERT appointment + INSERT outbox ──► COMMIT ──► 201
         all 3 failed ──► 409 "Booking failed"
```

---

## 3. API contract

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

- The client never sends `vehicleTypeId`, `endTime`, `serviceBayId` or `technicianId`. The server finds them.
- `endTime = startTime + serviceType.duration_minutes`.
- `vehicleTypeId` comes from the vehicle row (`vehicles.vehicle_type_id`).

---

## 4. Tasks

### Task 1 — Validate the request

**Goal:** stop bad input early, with a clear `400`, before we touch the database for writes.

Two levels. Shape checks run at the controller edge (Bean Validation). Business checks run in the service.

```
                 ┌──────────── controller edge (Bean Validation) ────────────┐
request ──►      │ body:   all 5 fields @NotNull                             │ ──► 400
                 │         startTime @Future                                 │
                 │ header: Idempotency-Key @NotBlank @Size(max = 128)        │
                 └───────────────────────────────────────────────────────────┘
                                         │ ok
                 ┌──────────── service (one read per check) ─────────────────┐
                 │ dealership, service type, vehicle, customer exist  ─────► │ 404
                 │ vehicle belongs to customer                        ─────► │ 400
                 │ [start, end) inside business hours (local time)    ─────► │ 422
                 │ vehicle has no overlapping appointment (G2)        ─────► │ 422
                 │ start is on the slot grid (G4)                     ─────► │ 422
                 └───────────────────────────────────────────────────────────┘

slot grid (G4): local start − open_time must be a multiple of slot-step
  open 08:00, slot-step 15m
  09:00 → 60 min  → 60 % 15 = 0  ok
  09:07 → 67 min  → 67 % 15 = 7  422
```

Rules:
- Use `spring-boot-starter-validation` (already in `server-api/build.gradle.kts`). The package is **`jakarta.validation`**. In Spring Boot 3+ the old `javax.validation` package does not exist any more; same annotations, new name.
- The request is a `record` (`BookAppointmentRequest`) with constraint annotations on its components. The controller method has `@Valid @RequestBody`.
- The header is a method parameter: `@RequestHeader(HeaderNames.IDEMPOTENCY_KEY) @NotBlank @Size(max = AppointmentConstants.IDEMPOTENCY_KEY_MAX) String key`. The controller class needs `@Validated` for this.
- `startTime` is `OffsetDateTime`. A time with no offset (`2026-11-02T09:00`) fails JSON parsing → `400`.
- Error bodies come only from `ApiExceptionHandler` (RFC 7807 `ProblemDetail`). Add a handler for `MethodArgumentNotValidException` / `HandlerMethodValidationException` that lists the bad fields:

```
400 Bad Request
{ "title": "Bad Request", "detail": "Invalid request",
  "errors": [ { "field": "startTime", "message": "must be a future date" } ] }
```

- Messages and limits live in `AppointmentConstants` / `ErrorMessages`, not as literals (CLAUDE.md §3).

**Acceptance criteria**
- [ ] AC1.1 Missing field, `null` id, bad UUID text, past `startTime` → `400` with the field name in `errors`.
- [ ] AC1.2 Missing or 129-char `Idempotency-Key` → `400`.
- [ ] AC1.3 Unknown dealership / service type / vehicle / customer → `404` with the entity name.
- [ ] AC1.4 Vehicle of another customer → `400`.
- [ ] AC1.5 Window outside business hours in the dealership's time zone → `422`.
- [ ] AC1.6 Vehicle already has an overlapping non-cancelled appointment → `422` "vehicle already booked"; no pair is tried.
- [ ] AC1.7 Known `Idempotency-Key` with a different body (any of the 5 fields differs) → `422`; same body → `201` with the stored appointment.
- [ ] AC1.8 `startTime` off the grid (e.g. 09:07 local, open 08:00, step 15m) → `422` "start must be on a slot"; 09:00 → passes. Checked in the dealership's local time, so it also holds on a DST day.
- [ ] AC1.9 Slot step comes from `scheduler.availability.slot-step` (default `15m`). Changing it to `30m` in a test makes 09:15 fail with `422`, and `GET /availability` shows only :00 / :30 starts.
- [ ] AC1.10 Controller slice test (`@WebMvcTest`) covers AC1.1–1.2 without a database.

---

### Task 2 — Find a free bay and a qualified technician (SQL)

**Goal:** get up to 3 ordered `(bay, technician)` pairs for the window, straight from PostgreSQL. Never from the Redis cache: the database decides bookings.

The algorithm already exists (`CandidateSelector`, `candidate-selection.md`). This task moves it into the Spec05 layout and makes the booking use it.

```
                         dealership D1, vehicle type V1, service S1
                         window [09:00, 10:00)

 fitting bays (SQL)              qualified technicians (SQL)
 active, takes V1                active, skill S1, certified V1
 B1  B2  B3                      T1  T2  T3
        │                               │
        └──────── busy in window? ──────┘     (SQL, tstzrange &&, status <> CANCELLED)
                 B3 busy                 T3 busy
        │                               │
 order: fewer vehicle types first   order: fewer jobs that local day first
 B2(1)  B1(3)                        T2(0)  T1(4)
        │                               │
        └──────────── pairs ────────────┘
               (B2,T2) (B1,T1) (B2,T1)
```

SQL (in `JdbcCandidateRepository` only):

```sql
-- fitting bays
SELECT b.id FROM service_bays b
JOIN service_bay_vehicle_types t ON t.service_bay_id = b.id
WHERE b.dealership_id = :dealershipId AND b.active AND t.vehicle_type_id = :vehicleTypeId;

-- qualified technicians
SELECT tc.id FROM technicians tc
JOIN technician_skills s        ON s.technician_id = tc.id AND s.service_type_id = :serviceTypeId
JOIN technician_vehicle_types v ON v.technician_id = tc.id AND v.vehicle_type_id = :vehicleTypeId
WHERE tc.dealership_id = :dealershipId AND tc.active;

-- busy resources in the window (same test as the exclusion constraints)
SELECT service_bay_id, technician_id FROM appointments
WHERE dealership_id = :dealershipId
  AND status <> 'CANCELLED'
  AND tstzrange(start_time, end_time, '[)') && tstzrange(:start, :end, '[)')
  AND (service_bay_id IN (:bayIds) OR technician_id IN (:technicianIds));
```

Package moves (CLAUDE.md §1, Spec05):

```
booking/CandidateSelector   ──► service/appointment/impl/CandidateSelector   (pure ordering helper)
booking/CandidateRepository ──► repository/appointment/CandidateRepository   (interface)
                                repository/appointment/impl/JdbcCandidateRepository
booking/CandidateResult     ──► model/appointment/CandidateResult
```

**Acceptance criteria**
- [ ] AC2.1 No fitting bay or no qualified technician → `422` "not serviceable".
- [ ] AC2.2 All fitting bays or all technicians busy → `409` "Booking failed" (same body as Task 3).
- [ ] AC2.3 Same database state → same pairs, same order (existing `CandidateSelectorIT` still green).
- [ ] AC2.4 A `CANCELLED` appointment does not make a resource busy.
- [ ] AC2.5 Back-to-back is fine: a job `[08:00, 09:00)` does not block `[09:00, 10:00)`.
- [ ] AC2.6 No SQL and no `JdbcClient` outside `repository/**/impl`.

---

### Task 3 — Submit the transaction and handle errors

**Goal:** save the appointment and its outbox event together, or save nothing. Let the database constraints be the final judge against double booking.

One transaction **per pair**. A failed pair rolls back only its own try.

```
for pair in pairs (max 3, no wait):
   BEGIN
     INSERT appointments (... status = CONFIRMED, idempotency_key = :key) RETURNING id
     INSERT outbox_events (type = AppointmentBooked, payload = {appointmentId, dealershipId, dates[local]})
   COMMIT ──────────────────────────────────────────────► 201 Created + Location
   on error:
     ROLLBACK, then by SQLSTATE:
       23P01 ex_appointments_bay / _technician ──► next pair
       23503 FK (bay/tech became inactive/removed) ─► next pair
       23P01 ex_appointments_vehicle ────────────► stop, 422 "vehicle already booked" (race after the Task 1 pre-check)
       23505 idempotency_key (two same-key calls race) ► read existing ──► same body: 201 / other body: 422
       other DataAccessException ────────────────► 503 (handler already exists)
after 3 failed pairs ──────────────────────────────────► 409 "Booking failed: ..."
```

Error table (all through `ApiExceptionHandler`):

| Case | Exception (new) | Status | `detail` |
|---|---|---|---|
| All pairs failed / none free | `BookingConflictException` | 409 | Spec04 §6 text, from `ErrorMessages` |
| Vehicle already booked at that time | `VehicleBusyException` | 422 | "The vehicle already has an appointment at this time." |
| Key reused with a different body | `IdempotencyKeyMismatchException` | 422 | "This Idempotency-Key was already used for a different request." |
| Not serviceable | `NotServiceableException` | 422 | "No bay or technician at this dealership can service this vehicle." |
| Outside business hours | `OutsideBusinessHoursException` | 422 | "The time is outside the dealership's business hours." |
| DB down | `DataAccessException` | 503 | existing handler |

Rules:
- The service does not touch SQL. It calls `AppointmentRepository.insert(...)` and `OutboxRepository.insert(...)` inside one transaction per try, using an injected `TransactionOperations` (an interface, so it fits CLAUDE.md §1).
- Map SQLSTATE in the repository implementation: it throws small domain exceptions (`SlotTakenException(resource)`, `DuplicateKeyException`), so the service never reads SQL codes.
- The outbox `dates` use the dealership's **local** date (Spec03 §5: local date ≠ UTC date).
- Write one log line per try (`pair`, `outcome`), never the customer data.
- Max tries comes from `application.yml` (`scheduler.booking.max-pairs: 3`) through a `@ConfigurationProperties` class, not a literal.

**Acceptance criteria**
- [ ] AC3.1 Happy path: `201`, `Location` header, one `appointments` row, one `outbox_events` row with type `AppointmentBooked`, both in one commit.
- [ ] AC3.2 First pair taken by a concurrent insert → second pair is booked → `201`.
- [ ] AC3.3 All 3 pairs fail → `409` with the Spec04 §6 text; no rows left behind.
- [ ] AC3.4 Same `Idempotency-Key` sent twice (one after the other, and at the same time) → both `201`, same `id`, one row.
- [ ] AC3.5 Outbox insert fails → appointment row is rolled back too.
- [ ] AC3.6 Concurrency IT: 20 parallel requests for one window with 2 free pairs → exactly 2 × `201`, 18 × `409`, no overlap in the table.

---

## 5. Files (Spec05 layout)

```
api/
├── controller/appointment/   AppointmentController
├── service/appointment/      AppointmentService
│   └── impl/                 AppointmentServiceImpl, CandidateSelector
├── repository/appointment/   AppointmentRepository, CandidateRepository, OutboxRepository
│   └── impl/                 JdbcAppointmentRepository, JdbcCandidateRepository, JdbcOutboxRepository
├── model/appointment/        CandidateResult, NewAppointment
├── dto/appointment/          BookAppointmentRequest, AppointmentResponse
├── exception/appointment/    BookingConflictException, VehicleBusyException, NotServiceableException, ...
├── constant/                 ApiPaths.APPOINTMENTS, HeaderNames.IDEMPOTENCY_KEY
│   └── appointment/          AppointmentConstants
└── config/appointment/       BookingProperties
```

## 6. Out of scope

- `GET /appointments/{id}`, cancel, reschedule (next stories).
- Rate limiting (`429`).
- Cache update: the outbox relay already bumps `avail:ver:*` (Spec04).

## 7. Definition of done

- [ ] All AC above have a test (unit for ordering/validation, IT with Testcontainers for SQL and concurrency).
- [ ] Javadoc on every public type and method, CLAUDE.md §4 style.
- [ ] DLD diagrams 03 and 11 updated if the review below changes the contract.
- [ ] Postman collection has the happy path and each error case.

## 8. Architecture review (Winston, 2026-11-01)

Checked against: DLD §5 / §11 / §3, Spec04 §6, Spec05 layout and RF-06, CLAUDE.md §1–§4, V2 schema.

### 8.1 What is sound

```
correctness chain
  candidate read (no lock) ──► INSERT ──► exclusion constraint decides
         may be stale              │            (bay / tech / vehicle)
                                   └── stale read costs one retry, never a double booking
```

- The read is not locked, and that is right: the GiST exclusion constraints are the only judge. No `SELECT … FOR UPDATE`, no `SERIALIZABLE` needed.
- One transaction per pair matches DLD §5 and Spec04 §6.
- Appointment + outbox in one commit keeps the cache invalidation reliable (outbox pattern).
- `TransactionOperations` in the service has a precedent: Spec05 RF-06 puts `TransactionTemplate` in the relay service.

### 8.2 Gaps

| # | Gap | Where it conflicts | Recommendation |
|---|---|---|---|
| G1 | Path `POST /api/v1/appointments` is flat; CLAUDE.md §2 wants owner nesting `/dealerships/{id}/appointments` | DLD §3 vs CLAUDE.md §2 (same as Spec05 Q1) | **Open — ask user** |
| G2 | DLD treats every `23P01` as "try next pair". A vehicle overlap (`ex_appointments_vehicle`) fails all 3 pairs the same way and wastes 3 transactions | DLD §5 | Pre-check the vehicle in Task 1, keep the `23P01` mapping as a safety net. **Ask user** for the status |
| G3 | Same `Idempotency-Key`, different body → DLD returns the old appointment with `201`. A client bug gets a wrong booking silently | DLD §5 | **Open — ask user** |
| G4 | Can `startTime` be any minute (09:07), or must it match a slot from `GET /availability`? | `SlotCalculator` grid vs booking | **Open — ask user** |
| G5 | `@Future` uses the JVM clock, not the `Clock` bean (`ClockConfig`, `MutableClock` in tests). Tests with a fixed clock will be flaky | Task 1 | Wire Hibernate Validator `ClockProvider` from the `Clock` bean, or do "in the future" in the service. Pick `ClockProvider` (keeps the check at the edge) |
| G6 | DLD §3 lists `201 | 400 | 404 | 409 | 429`; this story also answers `422` and `503` | DLD 03 vs DLD 11 | Doc fix: add `422`, `503` to diagram 03 |
| G7 | No upper limit on how far ahead a customer can book | Task 1 | Add `scheduler.booking.max-days-ahead` (yml) → `422`. Default open — follow availability's max range |
| G8 | `CandidateSelector` runs 5 reads per booking (dealership, duration, bays, techs, busy + 2 order counts) | Task 2 | OK for now (all indexed, small sets). Do not merge into one SQL until a load test shows need (Rule of Three) |
| G9 | Outbox `type` and payload shape are shared with `scheduler-batch` but live as plain strings | Spec04 §4.4 | Constant `OutboxEventTypes.APPOINTMENT_BOOKED` in server-api; payload test against `docs/contracts/outbox/AppointmentBooked.json` |

### 8.3 Decisions needed from the user

Answers are recorded below and the tasks above are updated to match.

- **G1 (decided):** build `POST /api/v1/appointments` first (this story). Move to `POST /api/v1/dealerships/{dealershipId}/appointments` in a follow-up story, together with Spec05 Q1 for availability.
- **G2 (decided):** vehicle overlap → `422` (business rule). Checked in Task 1 before any pair; `ex_appointments_vehicle` maps to the same `422` as a safety net.
- **G3 (decided):** same key + different body → `422`. Compare the 5 request fields with the stored row; no migration.
- **G4 (decided):** option A — `startTime` must be on the slot grid, else `422`. The step is **config, not a constant**:

```yaml
scheduler:
  availability:
    slot-step: 15m        # one value for availability AND booking
```

```
application.yml ──► AvailabilityProperties.slotStep (Duration, @ConfigurationProperties)
                          │
            ┌─────────────┴──────────────┐
            ▼                            ▼
  SlotCalculator (builds slots)   AppointmentServiceImpl (checks grid)
```

  - Remove `SlotCalculator.STEP_MINUTES`; pass the step in as a parameter (it stays a pure helper).
  - Both features read the **same** key. Two keys could drift, and then booking would reject slots that availability shows.
  - Validate at startup: `slot-step` > 0 and divides 24 h with no remainder (`@Validated` + a check) — a bad value fails fast, not on the first request.
  - After a step change, cached slot lists stay old for at most the cache TTL (30–60 s). Acceptable; no version bump needed.

### 8.4 G4 explained — slot grid vs any minute (decided: A)

Today `GET /availability` builds starts every **15 min** from the local `open_time` (`SlotCalculator.STEP_MINUTES`).
Example: dealership opens 08:00, closes 17:00, service lasts 60 min.

```
availability shows:  08:00  08:15  08:30  08:45  09:00 ... 16:00
                     │      │      │      │      │          (last start = close − 60 min)
```

| Input `startTime` (local) | A. Must match grid | B. Any minute |
|---|---|---|
| `09:00` (on grid, free) | `201` | `201` |
| `09:07` (off grid, free) | `422` "start must be on a 15-min slot" | `201`, job `[09:07, 10:07)` |
| `16:30` (on grid, ends 17:30 > close) | `422` outside business hours | `422` outside business hours |
| `07:45` (before open) | `422` | `422` |

What each choice causes later:

```
B. any minute — job [09:07, 10:07) on bay B1
   availability for B1:  09:00 x (overlaps)  09:15 x … 10:00 x  10:15 free
   → B1 loses 09:00 AND 10:00: about 2 slots blocked for 1 job (fragmentation)

A. grid only — job [09:00, 10:00) on bay B1
   availability for B1:  09:00 x … 09:45 x  10:00 free
   → what the customer saw is exactly what can be booked
```

- **A** keeps booking and availability in one rule, less wasted bay time. Cost: one more check, and the 15 min step becomes part of the API contract (should move to `application.yml`).
- **B** is simpler and more flexible (e.g. staff booking by phone), but wastes capacity and lets clients book times the UI never shows.
