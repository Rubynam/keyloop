# Brownfield — Availability Ops

## Existing (`server-api/src/main/java/com/keyloop/scheduler/api/`)

- `availability/AvailabilityService.java` — DB-only read (`spec-check-availability`); the cache wraps the per-date computation here.
- `availability/SlotCalculator.java` — currently takes `now` and filters; split so the cached computation runs without the now-cut and the cut runs after the cache.
- `availability/AvailabilityRepository.java` — fitting-bay / qualified-technician / overlap SQL; candidate selection reuses these queries for one window.
- `spring-boot-starter-data-redis` and Redis connection config already present (`application.yml`, readiness includes `redis`).

## Existing (`scheduler-batch/src/main/java/com/keyloop/scheduler/batch/`)

- `outbox/OutboxRelay.java` — placeholder `@Scheduled` method, `@ConditionalOnProperty(scheduler.outbox.relay.enabled)`; fill per Spec04 §4.
- `outbox/OutboxRelayProperties.java` — Spec04 §7 keys already bound.
- Schema: `V4__outbox_retry.sql` (`next_attempt_at`, `failed_at`, `last_error`, pending index).

## Tests

- `server-api/src/test/.../support/IntegrationTest` + `TestcontainersConfig` (PostgreSQL 18 + Redis 8, Spec04 TST-01); `scheduler-batch` has the same base.
- Payload samples: `docs/contracts/outbox/*.json` — relay tests read them.

## Diagrams (owned by DLD)

- `docs/diagrams/dld/04-seq-check-availability.mermaid`, `05-seq-book-appointment.mermaid`, `07-seq-outbox-relay.mermaid`, `10-redis-keys.mermaid`, `11-error-handling.mermaid`. Diagram 04 still shows the rate-limit step — moved to its own spec.

## Verify

- `./gradlew test`
