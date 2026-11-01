# Project rules (Keyloop scheduler)

These rules are mandatory for every Java change in `server-api`, `scheduler-batch` and `common-logging`.
When you touch a file that breaks them, fix that file in the same change.

## 1. Layering (MVC) — always through interfaces

```
Controller ──► XxxService (interface) ──► XxxServiceImpl
                                              │
                                              ▼
                                   XxxRepository (interface) ──► JdbcXxxRepository
```

- A controller depends **only** on a service interface. Never on a `*ServiceImpl`, a repository, `JdbcClient` or another controller.
- A service implementation depends **only** on repository interfaces (and other service interfaces). Never on `JdbcClient`, SQL or a `*Repository` implementation.
- SQL lives only in repository implementations.
- Inject by constructor. Field/`@Autowired` setter injection is not allowed.
- Spring stereotypes go on the implementation (`@Service`, `@Repository`), not on the interface.
- Request/response DTOs (records) never leak repository row types to the controller; map in the service.
- Pure helpers with no I/O (e.g. `SlotCalculator`) may stay concrete classes, but are used from the service layer only.

Package layout: **layer first, then feature**.
- Code used by more than one feature sits at the layer root.
- Feature code sits in `<layer>/<feature>/`.
- Implementations sit in `<layer>/<feature>/impl/`.

```
server-api  (com.keyloop.scheduler.api)
├── controller/
│   ├── availability/  AvailabilityController
│   └── ping/          PingController
├── service/
│   ├── availability/  AvailabilityService (interface)
│   │   └── impl/      AvailabilityServiceImpl, SlotCalculator (pure helper)
│   └── appointment/ CandidateSelector (interface)
│       └── impl/      CandidateSelectorImpl
├── repository/
│   ├── availability/  AvailabilityRepository (interface)
│   │   └── impl/      JdbcAvailabilityRepository
│   └── appointment/ CandidateRepository (interface)
│       └── impl/      JdbcCandidateRepository
├── cache/             Redis ports, same shape as repository/
│   └── availability/  AvailabilityCache (interface)
│       └── impl/      RedisAvailabilityCache
├── model/             shared types (AppointmentStatus)
│   ├── availability/  Dealership, Booking, Interval (rows used by service + repository)
│   └── appointment/ CandidateResult
├── dto/
│   ├── availability/  AvailabilityResponse (HTTP only)
│   └── ping/          PingResponse
├── exception/         NotFoundException (shared)
│   └── availability/  InvalidRangeException
├── constant/          ApiPaths, HeaderNames, ErrorMessages (shared)
│   ├── availability/  AvailabilityConstants
│   └── appointment/   AppointmentConstants
├── config/            ClockConfig
│   └── availability/  AvailabilityConfig, AvailabilityCacheProperties
└── web/               ApiExceptionHandler, RequestIdFilter (cross-cutting HTTP infra)

scheduler-batch  (com.keyloop.scheduler.batch)
├── schedule/          JobScheduler, OutboxRelay (@Scheduled entry points = "controllers")
├── job/               Spring Batch wiring + tasklets (also "controllers")
│   ├── heartbeat/     HeartbeatJobConfig, HeartbeatTasklet
│   └── outbox/        OutboxCleanupJobConfig
├── service/
│   ├── heartbeat/     HeartbeatService (interface)
│   │   └── impl/      HeartbeatServiceImpl
│   └── outbox/        OutboxRelayService (interface)
│       └── impl/      OutboxRelayServiceImpl
├── repository/
│   ├── heartbeat/     HeartbeatProbe (interface)
│   │   └── impl/      JdbcRedisHeartbeatProbe
│   └── outbox/        OutboxEventRepository (interface)
│       └── impl/      JdbcOutboxEventRepository
├── store/             Redis-backed ports, same shape as repository/
│   └── outbox/        AvailabilityVersionStore (interface)
│       └── impl/      RedisAvailabilityVersionStore
├── model/
│   └── outbox/        OutboxEvent
├── exception/
│   └── outbox/        InvalidPayloadException
├── constant/          JobNames, SchedulingConstants
│   └── outbox/        RelayConstants
└── config/            SchedulingConfig
    └── outbox/        OutboxRelayProperties, OutboxCleanupProperties
```

In `scheduler-batch`, `@Scheduled` classes (`schedule/`) and tasklets (`job/<feature>/`) play the controller role: they call a service interface only. Redis-backed ports there live in `store/`.

`ArchitectureTest` in each app (ArchUnit, Spec05 §7 ARC-01…08) fails the build when these rules break.

Full target layout and migration plan: [planing/Spec05.md](planing/Spec05.md).

## 2. Controllers follow RESTful API rules

- Base path is versioned: `/api/v1`. Take it from a constant (see §3), never a string literal.
- Paths are **plural nouns**, kebab-case, no verbs: `/dealerships/{dealershipId}/appointments`, not `/createAppointment`.
- Sub-resources are nested under their owner when the owner id is required.
- HTTP methods by meaning:
  | Method | Use | Success status |
  |---|---|---|
  | `GET` | read, no side effects | `200` |
  | `POST` | create | `201` + `Location` header |
  | `PUT` | full replace | `200` / `204` |
  | `PATCH` | partial update | `200` |
  | `DELETE` | remove / cancel | `204` |
- Error statuses: `400` bad input, `404` unknown id, `409` conflict (e.g. slot already taken), `422` valid shape but business rule fails. Errors go through `ApiExceptionHandler` only; controllers do not build error bodies.
- Return DTOs (or `ResponseEntity<Dto>` when status/headers matter). Never return entities, row records or `Map`.
- Validate input with Bean Validation (`@Valid`, `@NotNull`, ...) at the controller edge; business checks belong in the service.
- No business logic in controllers: one call to the service, then return.

## 3. No hard-coding — use constants

- No magic strings or numbers in logic: paths, header names, error codes/messages, cache keys, limits, durations.
- Put them in a `final` class with a private constructor, per feature (`AvailabilityConstants`) or shared (`ApiPaths`, `HeaderNames`, `ErrorCodes`).
- Values that can change per environment (timeouts, retry counts, TTLs, cron) go in `application.yml` + a `@ConfigurationProperties` class (like `OutboxRelayProperties`), not in constants.
- Allowed literals: `0`, `1`, `-1`, empty string, and SQL text inside repository implementations.

```java
public final class ApiPaths {
    public static final String API_V1 = "/api/v1";
    public static final String AVAILABILITY = "/availability";
    private ApiPaths() {}
}
```

## 4. Javadoc — short and in two parts

Every public interface, class and public method of controllers/services/repositories has Javadoc.
Write in simple English (about 70% B1, 30% B2 level). No long sentences, no academic words.

**Part 1 — What and why (2–4 lines):** what this is, what role it plays, which business need it serves.
**Part 2 — Example:** a small ASCII diagram or input → output sample. Do not explain it in long text.

```java
/**
 * Finds free booking slots for one dealership, service type and vehicle type.
 * Customers use it to pick a time before they book. It only reads data and
 * never holds a slot.
 *
 * <pre>
 * GET /api/v1/availability?dealershipId=D1&serviceTypeId=S1&vehicleTypeId=V1
 *     &from=2026-11-02&to=2026-11-03
 *
 * 2026-11-02  09:00 ──[bay B1 / tech T1]── 10:00   free
 *             10:00 ──[booked]──────────── 11:00   x
 * 2026-11-03  09:00 ──[bay B2 / tech T3]── 10:00   free
 * </pre>
 */
```

- Tag `@param` / `@return` / `@throws` only when the meaning is not obvious from the name.
- Spec/DLD references (e.g. `DLD §8`) go at the end of Part 1, in one short line.
- Inline comments explain *why*, not *what*.
