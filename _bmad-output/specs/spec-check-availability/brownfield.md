# Brownfield — Check Availability

CAP-1…4 are implemented (DB-only). CAP-5 (Postman) is retired. CAP-6 integration test is **not yet written**.

## Code map (`server-api/src/main/java/com/keyloop/scheduler/api/`)

- `availability/AvailabilityController.java` — `GET /api/v1/availability`, `@RequestParam` UUID / `LocalDate` binding.
- `availability/AvailabilityService.java` — range check (`MAX_DAYS = 14`), 404 lookups, one appointment load for the UTC span local `from` 00:00 → local `to + 1` 00:00, grouped by resource id.
- `availability/AvailabilityRepository.java` — all SQL (`JdbcClient`): dealership, service duration, vehicle-type existence, fitting active bays, qualified active technicians, overlapping non-cancelled appointments.
- `availability/SlotCalculator.java` — pure function: hours + zone + duration + busy intervals + now → slots per local date.
- `availability/AvailabilityResponse.java`, `NotFoundException.java`, `InvalidRangeException.java`; `web/ApiExceptionHandler.java` — 404 / 400 ProblemDetail.
- `domain/AppointmentStatus.java` — use `CANCELLED.name()` in queries.

## Schema read (`server-api/src/main/resources/db/migration/V2__domain_schema.sql`)

`dealerships(timezone, open_time, close_time)`, `service_types(duration_minutes)`, `vehicle_types`, `service_bays(active)` + `service_bay_vehicle_types`, `technicians(active)` + `technician_skills` + `technician_vehicle_types`, `appointments(start_time, end_time, status, technician_id, service_bay_id)`. Exclusion constraints on `technician_id`, `service_bay_id`, `vehicle_id` ignore `CANCELLED`.

## Tests

- Existing: `availability/SlotCalculatorTest`, `AvailabilityServiceTest`, `AvailabilityControllerTest` (`@WebMvcTest`, Boot 4 package `org.springframework.boot.webmvc.test.autoconfigure`).
- Integration base: `support/IntegrationTest` + `support/TestcontainersConfig` (Postgres 18 + Redis 8, Spec04 TST-01) — CAP-6 extends this.

## Not part of the contract

- `postman/` collection and `seeding/` data still exist for ad-hoc manual checks; tests must not depend on them.

## Verify

- `./gradlew :server-api:test`
