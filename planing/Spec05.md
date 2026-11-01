# Spec05 — Layered Refactor: MVC Through Interfaces, REST, Constants, Javadoc

> Keyloop Technical Assessment · Scenario A: The Unified Service Scheduler
> Roadmap: **Phase 3 — hardening (cross-cutting)** · Applies the project rules in [CLAUDE.md](../CLAUDE.md) to the existing code
> Status: 📝 Draft · Owner: Dai Nam

---

## 1. Goal

Bring every class in `server-api` and `scheduler-batch` in line with [CLAUDE.md](../CLAUDE.md) §1–§4, **without changing any behaviour**. When Spec05 is done:

1. Every controller talks to a **service interface**, and every service talks to a **repository interface** (or a cache / store interface). No class outside a repository implementation touches `JdbcClient`, `JdbcTemplate` or `StringRedisTemplate`.
2. No magic strings or numbers are left in logic: paths, header names, error messages, lock names, limits.
3. Every public type and method of a controller, service or repository has the two-part Javadoc (what + why, then example).
4. An **ArchUnit** test fails the build if a later change breaks rule 1, so the rules stay true without manual review.

The HTTP contract, the SQL, the Redis key layout, the relay retry policy and every test result stay exactly the same.

---

## 2. Precondition and order

```
main ──●── merge claude/feat-availability-ops-193f62 ──●── Spec05 PR1 ── PR2 ── PR3 ── PR4 ── PR5
       │                                                 (this spec starts here)
       └── feat/read-availability (already in ops branch)
```

| # | Rule | Why |
|---|---|---|
| P1 | Spec05 starts **after** branch `claude/feat-availability-ops-193f62` (cache, candidate selection, relay) is merged to `main`. | That branch changes ~2,500 lines in the same files (`AvailabilityService`, `AvailabilityRepository`, `OutboxRelay`, `ApiExceptionHandler`). Moving those files first would turn the merge into a conflict on every line. |
| P2 | No feature work runs in parallel on the files in §4 while a Spec05 PR is open. | Package moves conflict with any edit to the moved file. |
| P3 | Each PR in §9 is green on `./gradlew test` on its own. | A refactor that is only correct at the end cannot be reviewed or reverted step by step. |

---

## 3. Decisions

| # | Question | Decision | Why |
|---|---|---|---|
| D1 | Package by layer or by feature first? | **By layer first, then by feature inside it** (`controller/availability`, `service/availability`, …). Shared code sits at the layer root; feature code in `<layer>/<feature>/`; implementations in `<layer>/<feature>/impl/`. | The MVC layers are visible at the top of the tree, so a reader sees the architecture before the features, and ArchUnit's `layeredArchitecture()` maps 1:1 to the top-level packages (§7). Cost: one feature is spread over several top-level folders. |
| D2 | Where do repository row types (`Dealership`, `Booking`) go? | New `model/<feature>/` package. Services and repositories share them; controllers never see them. | They are not HTTP DTOs (`dto/`) and must not stay nested in the repository, or the service interface would import the repository implementation. **CLAUDE.md §1 layout gets a `model/` line** (RF-14). |
| D3 | `GET /api/v1/availability?dealershipId=…` is not nested under `/dealerships/{id}`. Change it now? | **No.** Keep the path in Spec05. | Spec05 is behaviour-preserving. Changing the path breaks `spec-check-availability`, the DLD API contract, Postman and nginx tests. Recorded as open question Q1 (§11). |
| D4 | Keep the 5 `@RequestParam`s or bind one `AvailabilityQuery` record? | **Keep the 5 params**, same service signature. | Same reason as D3: no contract or log change. A query object can come with Q1. |
| D5 | Implementation naming | `XxxServiceImpl`, `JdbcXxxRepository`, `RedisXxx` for Redis-backed ports. | The prefix says the technology; the interface keeps the business name. |
| D6 | `MethodLoggingAspect` logs `getDeclaringType().getSimpleName()`, which becomes `AvailabilityServiceImpl`. | **Accept** the new name. No aspect change. | Logs are not a contract. Stripping `Impl` in the aspect is a hidden rule that surprises the next reader. |
| D7 | `Clock`: two constructors in `AvailabilityService` today. | **One constructor**, `Clock` injected from the bean that the ops branch already defines; move that bean to `config/ClockConfig`. | CLAUDE.md §1: constructor injection only. Two constructors + `@Autowired` exist only to let tests pass a clock, and the bean already does that. |
| D8 | Scheduler entry points (`JobScheduler`, `OutboxRelay` `@Scheduled`, `HeartbeatTasklet`) | Treated as **controllers**: they only call a service interface (or Spring Batch's `JobOperator`). | They are the "input edge" of the batch app, the same role a controller has in the API. |
| D9 | `ProblemDetail` titles `"Not Found"`, `"Bad Request"` | Use `HttpStatus.getReasonPhrase()`; no new constant. | The framework already owns that text; a constant would only copy it. |
| D10 | How to keep the rules true later? | **ArchUnit** test in each app (§7). | A rule only in a Markdown file breaks on the first busy day. |

---

## 4. Current state → target (inventory)

Line refs are from `main` after P1. ⛔ = breaks a CLAUDE.md rule.

### 4.1 `server-api`

Paths are relative to `com.keyloop.scheduler.api`.

| Today | Problem | Target |
|---|---|---|
| `availability/AvailabilityController` | ⛔ depends on concrete `AvailabilityService`; ⛔ `"/api/v1"`, `"/availability"` literals; Javadoc not 2-part | `controller/availability/AvailabilityController` → `service/availability/AvailabilityService` (interface); paths from `constant/ApiPaths` |
| `availability/AvailabilityService` (`@Service` class) | ⛔ no interface; ⛔ depends on concrete repository + cache; ⛔ `MAX_DAYS = 14`, entity names `"Dealership"`… literals; ⛔ 2 constructors | `service/availability/AvailabilityService` (interface) + `service/availability/impl/AvailabilityServiceImpl` |
| `availability/AvailabilityRepository` (`@Repository` class + nested records) | ⛔ no interface; row records nested | `repository/availability/AvailabilityRepository` (interface) + `repository/availability/impl/JdbcAvailabilityRepository`; records → `model/availability/` |
| `availability/AvailabilityCache` (`@Component`, Redis) | ⛔ service depends on concrete Redis class; `FAIL_OPEN_EVENT` constant on the class | `cache/availability/AvailabilityCache` (interface) + `cache/availability/impl/RedisAvailabilityCache`; event name → `constant/availability/AvailabilityConstants` |
| `availability/AvailabilityCacheProperties` | ok | `config/availability/AvailabilityCacheProperties` |
| `availability/AvailabilityConfig` (clock bean + Lua literal) | Lua script as Java string; clock is not availability-specific | Clock → `config/ClockConfig`; Lua → `src/main/resources/redis/release-lock.lua`, loaded by `config/availability/AvailabilityConfig` |
| `availability/AvailabilityResponse` | ok, package only | `dto/availability/AvailabilityResponse` |
| `availability/SlotCalculator` | `STEP_MINUTES` constant on the calculator | `service/availability/impl/SlotCalculator` (pure, package-private use); `STEP_MINUTES` → `AvailabilityConstants` |
| `availability/InvalidRangeException` | message built with literals in service | `exception/availability/InvalidRangeException`; message formats → `AvailabilityConstants` |
| `availability/NotFoundException` | used by booking too, but lives in availability | `exception/NotFoundException` (shared, layer root); format → `constant/ErrorMessages` |
| `booking/CandidateSelector` (`@Service` class) | ⛔ no interface; ⛔ depends on concrete `AvailabilityRepository`, `CandidateRepository`; `MAX_PAIRS = 3` | `service/booking/CandidateSelector` (interface) + `service/booking/impl/CandidateSelectorImpl`; `MAX_PAIRS` → `constant/booking/BookingConstants` |
| `booking/CandidateRepository` | ⛔ no interface | `repository/booking/CandidateRepository` + `repository/booking/impl/JdbcCandidateRepository` |
| `booking/CandidateResult` | ok, package only | `model/booking/CandidateResult` |
| `web/PingController` | ⛔ `"/api/v1"` literal; ⛔ returns `Map` | `controller/ping/PingController` returning `dto/ping/PingResponse(String status)`; same JSON `{"status":"ok"}` |
| `web/ApiExceptionHandler` | imports exceptions from `availability`; title literals | stays in `web/` (cross-cutting HTTP infra, not a layer); imports from `exception/..`; titles per D9 |
| `web/RequestIdFilter` | `HEADER = "X-Request-Id"` on the filter | stays in `web/`; header → `constant/HeaderNames.REQUEST_ID` |
| `domain/AppointmentStatus` | ok | `model/AppointmentStatus` (shared, layer root) |

### 4.2 `scheduler-batch`

Paths are relative to `com.keyloop.scheduler.batch`.

| Today | Problem | Target |
|---|---|---|
| `outbox/OutboxRelay` (~230 lines: `@Scheduled` + SQL + Redis + retry) | ⛔ entry point, business logic, SQL and Redis in one class | `schedule/OutboxRelay` (only `@Scheduled`, calls service) → `service/outbox/OutboxRelayService` + `impl/OutboxRelayServiceImpl` (tick, retry policy, transaction) → `repository/outbox/OutboxEventRepository` + `impl/JdbcOutboxEventRepository` (SKIP LOCKED select, mark processed / retry / failed) and `store/outbox/AvailabilityVersionStore` + `impl/RedisAvailabilityVersionStore` (`INCR` + `EXPIRE`) |
| `OutboxRelay.InvalidPayloadException` (nested) | nested in entry point | `exception/outbox/InvalidPayloadException` |
| `outbox/*Properties` | ok | `config/outbox/OutboxRelayProperties`, `config/outbox/OutboxCleanupProperties` |
| `job/HeartbeatTasklet` (JdbcTemplate + Redis) | ⛔ data access in the entry point; `"SELECT 1"` | `job/heartbeat/HeartbeatTasklet` → `service/heartbeat/HeartbeatService` + `impl/HeartbeatServiceImpl` → `repository/heartbeat/HeartbeatProbe` (interface) + `impl/JdbcRedisHeartbeatProbe` |
| `job/HeartbeatJobConfig` | `"heartbeatStep"` literal | `job/heartbeat/HeartbeatJobConfig`; names → `constant/JobNames` |
| `schedule/JobScheduler` | `"heartbeatJob"`, `"PT25S"`, `"PT10S"`, `"run.at"` literals | stays in `schedule/`; names / lock durations / param key → `constant/JobNames`, `constant/SchedulingConstants` |
| `job/OutboxCleanupJobConfig` | placeholder | `job/outbox/OutboxCleanupJobConfig` (move only) |
| `config/SchedulingConfig` | `"PT5M"` literal | stays; → `SchedulingConstants.DEFAULT_LOCK_AT_MOST_FOR` |

`common-logging` is out of scope: it holds no controller, service or repository. Only its Javadoc is touched (RF-13).

---

## 5. Target layout

Three placement rules:

| Rule | Example |
|---|---|
| Layer first, at the top of the module package | `controller/`, `service/`, `repository/`, `dto/`, … |
| Code used by more than one feature sits at the **layer root** | `exception/NotFoundException`, `constant/ApiPaths` |
| Feature code sits in `<layer>/<feature>/`; implementations in `<layer>/<feature>/impl/` | `service/availability/impl/AvailabilityServiceImpl` |

```
server-api/src/main/java/com/keyloop/scheduler/api/
├── controller/
│   ├── availability/  AvailabilityController
│   └── ping/          PingController
├── service/
│   ├── availability/  AvailabilityService
│   │   └── impl/      AvailabilityServiceImpl, SlotCalculator
│   └── booking/       CandidateSelector
│       └── impl/      CandidateSelectorImpl
├── repository/
│   ├── availability/  AvailabilityRepository
│   │   └── impl/      JdbcAvailabilityRepository
│   └── booking/       CandidateRepository
│       └── impl/      JdbcCandidateRepository
├── cache/
│   └── availability/  AvailabilityCache
│       └── impl/      RedisAvailabilityCache
├── model/             AppointmentStatus
│   ├── availability/  Dealership, Booking, Interval
│   └── booking/       CandidateResult
├── dto/
│   ├── availability/  AvailabilityResponse
│   └── ping/          PingResponse
├── exception/         NotFoundException
│   └── availability/  InvalidRangeException
├── constant/          ApiPaths, HeaderNames, ErrorMessages
│   ├── availability/  AvailabilityConstants
│   └── booking/       BookingConstants
├── config/            ClockConfig
│   └── availability/  AvailabilityConfig, AvailabilityCacheProperties
└── web/               ApiExceptionHandler, RequestIdFilter   (cross-cutting HTTP infra)

scheduler-batch/src/main/java/com/keyloop/scheduler/batch/
├── schedule/          JobScheduler, OutboxRelay              (@Scheduled entry points = "controllers")
├── job/                                                      (Spring Batch wiring + tasklets)
│   ├── heartbeat/     HeartbeatJobConfig, HeartbeatTasklet
│   └── outbox/        OutboxCleanupJobConfig
├── service/
│   ├── heartbeat/     HeartbeatService
│   │   └── impl/      HeartbeatServiceImpl
│   └── outbox/        OutboxRelayService
│       └── impl/      OutboxRelayServiceImpl
├── repository/
│   ├── heartbeat/     HeartbeatProbe
│   │   └── impl/      JdbcRedisHeartbeatProbe
│   └── outbox/        OutboxEventRepository
│       └── impl/      JdbcOutboxEventRepository
├── store/
│   └── outbox/        AvailabilityVersionStore
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

Call flow after the refactor:

```
HTTP GET /api/v1/availability
  │
  ▼
AvailabilityController ──► AvailabilityService ──► AvailabilityServiceImpl
                                                     ├──► AvailabilityCache      ──► RedisAvailabilityCache
                                                     ├──► AvailabilityRepository ──► JdbcAvailabilityRepository
                                                     └──► SlotCalculator (pure)

@Scheduled tick
  │
  ▼
OutboxRelay ──► OutboxRelayService ──► OutboxRelayServiceImpl  [TransactionTemplate per batch]
                                         ├──► OutboxEventRepository    ──► JdbcOutboxEventRepository
                                         └──► AvailabilityVersionStore ──► RedisAvailabilityVersionStore
```

---

## 6. Constants catalogue

| Class | Constant | Value | Replaces |
|---|---|---|---|
| `ApiPaths` | `API_V1` | `"/api/v1"` | `@RequestMapping` in both controllers |
| | `AVAILABILITY` | `"/availability"` | `@GetMapping` |
| | `PING` | `"/ping"` | `@GetMapping` |
| `HeaderNames` | `REQUEST_ID` | `"X-Request-Id"` | `RequestIdFilter.HEADER` |
| `ErrorMessages` | `NOT_FOUND` | `"%s %s not found"` | `NotFoundException` ctor |
| | `ENTITY_DEALERSHIP` / `ENTITY_SERVICE_TYPE` / `ENTITY_VEHICLE_TYPE` | `"Dealership"` / `"Service type"` / `"Vehicle type"` | service literals |
| `AvailabilityConstants` | `MAX_DAYS` | `14` | `AvailabilityService.MAX_DAYS` |
| | `STEP_MINUTES` | `15` | `SlotCalculator.STEP_MINUTES` |
| | `RANGE_TO_BEFORE_FROM` | `"'to' (%s) is before 'from' (%s)"` | `validateRange` |
| | `RANGE_TOO_LONG` | `"Range is %d days; at most %d are allowed"` | `validateRange` |
| | `INVALID_TIME_ZONE` | `"Dealership has an invalid time zone: %s"` | `zoneOf` |
| | `FAIL_OPEN_EVENT` | `"availability.cache.fail-open"` | `AvailabilityCache.FAIL_OPEN_EVENT` |
| | Redis key prefixes (`avail:ver:`, `avail:slots:`, `avail:lock:`) | as in `cache-read-path.md` | literals in `AvailabilityCache` |
| `BookingConstants` | `MAX_PAIRS` | `3` | `CandidateSelector.MAX_PAIRS` |
| `JobNames` | `HEARTBEAT_JOB` / `HEARTBEAT_STEP` / `OUTBOX_CLEANUP_JOB` | `"heartbeatJob"` / `"heartbeatStep"` / `"outboxCleanupJob"` | `HeartbeatJobConfig.JOB_NAME`, step literal, `@SchedulerLock(name)` |
| `SchedulingConstants` | `RUN_AT_PARAM` | `"run.at"` | `JobScheduler.launch` |
| | `HEARTBEAT_LOCK_AT_MOST_FOR` / `HEARTBEAT_LOCK_AT_LEAST_FOR` | `"PT25S"` / `"PT10S"` | `@SchedulerLock` |
| | `DEFAULT_LOCK_AT_MOST_FOR` | `"PT5M"` | `@EnableSchedulerLock` |
| `RelayConstants` (`constant/outbox`) | Redis version key prefix, error kinds, §4.6 message formats | from Spec04 §4 | literals in `OutboxRelay` |

Message text **must not change by one character**: tests and Spec04 §4.6 assert it. Each constant is `public static final` in a `final` class with a private constructor (`@NoArgsConstructor(access = PRIVATE)` where Lombok is already used, as in `CorrelationId`).

Values that already live in `application.yml` (`poll-delay`, `retry-delay`, TTLs, lock wait, cron) stay there. They are not copied into constants.

---

## 7. Architecture tests (ArchUnit)

Add `testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")` to both apps. One test class per app: `ArchitectureTest`.

| ID | Rule (ArchUnit) |
|---|---|
| ARC-01 | `layeredArchitecture()`: layer `Controller` = `..controller..`, `..schedule..`, `..job..`; `Service` = `..service..`; `Persistence` = `..repository..`, `..cache..`, `..store..`. Controller may not be accessed by any layer; Service only by Controller; Persistence only by Service. |
| ARC-02 | Classes outside `..impl..` do not depend on classes in `..impl..` (except Spring wiring in `..config..`). Callers only see interfaces. |
| ARC-03 | Only classes in `..repository..impl..`, `..cache..impl..`, `..store..impl..` (and `..config..`) may depend on `JdbcClient`, `JdbcTemplate`, `NamedParameterJdbcTemplate`, `StringRedisTemplate`, `RedisTemplate`. |
| ARC-04 | `@Service` classes reside in `..service..impl..`; `@Repository` in `..repository..impl..`; `@RestController` in `..controller..`. |
| ARC-05 | Every top-level type in `..service.*`, `..repository.*`, `..cache.*`, `..store.*` (feature package, not `impl`) is an interface. |
| ARC-06 | No field injection: no field annotated `@Autowired`. |
| ARC-07 | No cycles between features: `slices().matching("..(api\|batch).*.(*)..")` — the feature name is the 2nd segment under the module, across every layer. |
| ARC-08 | Package shape: every class is in a known layer package (`controller`, `service`, `repository`, `cache`, `store`, `model`, `dto`, `exception`, `constant`, `config`, `web`, `schedule`, `job`) or is the `*Application` class. Catches a new feature folder created at the top level. |

---

## 8. Requirements

### 8.1 Layering

| ID | Requirement |
|---|---|
| RF-01 | Each class in §4 moves to its target package. Moves use `git mv` so history follows the file. |
| RF-02 | Extract one interface per service, repository, cache and store in §5. The interface holds only the methods that callers outside the implementation use today. Package-private helpers stay on the implementation. |
| RF-03 | `@Service` / `@Repository` / `@Component` move to the implementation. Interfaces carry no Spring annotation. `@Transactional` stays on the implementation method. |
| RF-04 | `AvailabilityServiceImpl` has one constructor `(AvailabilityRepository, AvailabilityCache, Clock)`. `AvailabilityServiceTest` builds it with fakes or Mockito mocks of the interfaces and a fixed `Clock`. |
| RF-05 | `CandidateSelectorImpl` depends on `AvailabilityRepository` and `CandidateRepository` interfaces only. |
| RF-06 | `OutboxRelay` keeps the `@Scheduled` method and `@ConditionalOnProperty`. Its body is one call: `relayService.tick()`. The SQL, Redis calls, retry policy and `TransactionTemplate` move to the service / repository / store as in §5. **Spec04 §4 behaviour is unchanged**: one transaction per batch, `FOR UPDATE SKIP LOCKED`, Postgres `now()`, 1 + 3 tries 2 s apart. |
| RF-07 | `HeartbeatTasklet` calls `HeartbeatService.check()` only. The `SELECT 1` and Redis `PING` move to `JdbcRedisHeartbeatProbe`. The log line stays `heartbeat ok: postgres={} redis={}`. |
| RF-08 | `PingController` returns `PingResponse("ok")`. Response JSON is byte-equal to today's `{"status":"ok"}`. |

### 8.2 Constants

| ID | Requirement |
|---|---|
| RF-09 | Every literal listed in §6 is replaced by its constant. Annotation values (`@RequestMapping`, `@SchedulerLock`) use the constant directly (they are compile-time constants). |
| RF-10 | The Lua release script moves to `server-api/src/main/resources/redis/release-lock.lua`, loaded with `new DefaultRedisScript<>(new ClassPathResource(...), Long.class)`. Script text is unchanged. |
| RF-11 | After the refactor, this check finds no string literal in `controller`, `service` or `store` packages other than log patterns: `grep -rnE '"[^"]{2,}"' --include=*.java */src/main/java \| grep -E '/(controller\|service\|store)/' \| grep -v 'log\.'` |

### 8.3 Javadoc

| ID | Requirement |
|---|---|
| RF-12 | Every public interface and public method in `..controller..`, `..service..`, `..repository..`, `..cache..`, `..store..` gets the two-part Javadoc of CLAUDE.md §4. Javadoc lives on the **interface**; the implementation uses `{@inheritDoc}` only when it adds a note (e.g. which SQL index it relies on). |
| RF-13 | Existing Javadoc facts are kept (DST rules in `SlotCalculator`, TTL padding / version key / lock explanations in the cache, Spec/DLD refs). They are rewritten into the two-part shape, not deleted. |
| RF-14 | CLAUDE.md §1 package layout is layer-first and lists `model/`, `cache/`, `store/`, `config/`, as in §5. |

Example for RF-12 (the service interface):

```java
/**
 * Finds free booking slots for one dealership, service type and vehicle type.
 * Customers call it before they book, to pick a time. It only reads data and
 * never holds a slot. DLD §8, spec-check-availability.
 *
 * <pre>
 * find(D1, OIL_CHANGE, SUV, 2026-11-02, 2026-11-02)
 *
 *   bays  B1 ██░░░░  B2 ░░░░██        █ busy  ░ free
 *   techs T1 ░░██░░
 *         09 10 11 12 13 14
 *   slots → 09:00, 11:00, 12:00   (60 min, one bay AND one tech free)
 * </pre>
 *
 * @throws NotFoundException     unknown dealership, service type or vehicle type
 * @throws InvalidRangeException {@code to < from} or more than 14 days
 */
public interface AvailabilityService {
    AvailabilityResponse find(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId,
                              LocalDate from, LocalDate to);
}
```

### 8.4 Tests

| ID | Requirement |
|---|---|
| TST-01 | No test is deleted, and no assertion is changed except imports, class names (`*Impl`) and constructor calls. |
| TST-02 | `AvailabilityControllerTest` keeps `@MockitoBean AvailabilityService`, now the interface. |
| TST-03 | `AvailabilityServiceTest.validateRange` tests call `AvailabilityServiceImpl.validateRange` (stays package-private static). Test packages mirror main: the test moves to `service.availability.impl`. |
| TST-04 | All Testcontainers ITs (`AvailabilityCacheIT`, `AvailabilityFailOpenIT`, `AvailabilityRepositoryIT`, `CandidateSelectorIT`, `OutboxRelayIT`, `OutboxRelayRedisDownIT`, `SchemaMigrationTest`) pass unchanged in behaviour. |
| TST-05 | `ArchitectureTest` (§7) exists in both apps and passes. |
| TST-06 | Any test asserting a log line with the class name `AvailabilityService` is updated to `AvailabilityServiceImpl` (D6). |

---

## 9. Delivery plan (one PR each, in order)

| PR | Scope | Done when |
|---|---|---|
| PR1 | Shared layer-root code: `constant/`, `exception/NotFoundException`, `model/AppointmentStatus`, `config/ClockConfig`; ping controller + dto; `web/` filter and handler | `./gradlew :server-api:test` green; `/api/v1/ping` JSON unchanged |
| PR2 | Availability across all layers (§4.1 rows 1–9) | green; Postman availability requests return identical bodies |
| PR3 | Booking across all layers | green; `CandidateSelectorIT` unchanged |
| PR4 | `scheduler-batch` split (relay, heartbeat, scheduler constants) | `./gradlew :scheduler-batch:test` green; relay retry ITs unchanged |
| PR5 | ArchUnit in both apps; CLAUDE.md layout update (RF-14); RF-11 grep clean | `./gradlew test` green with ARC-01…08 |

Each PR description lists the moved files (`git diff -M --stat`) so the reviewer sees moves, not rewrites.

---

## 10. Acceptance criteria

- [ ] `./gradlew test` passes for all modules, including every Testcontainers IT.
- [ ] `ArchitectureTest` passes in `server-api` and `scheduler-batch` (ARC-01…08).
- [ ] `GET /api/v1/availability` and `GET /api/v1/ping` return byte-equal bodies and the same status codes for the Postman collection, before and after.
- [ ] Relay behaviour matches Spec04 §4 (proved by `OutboxRelayIT`, `OutboxRelayRedisDownIT`).
- [ ] RF-11 grep finds nothing.
- [ ] Every public type/method in the layer packages has the two-part Javadoc (review checklist on PR2–PR4).
- [ ] No change to SQL text, Flyway migrations, `application.yml` keys or Redis key layout.

---

## 11. Open questions

| # | Question | Recommendation |
|---|---|---|
| Q1 | Move availability to `GET /api/v1/dealerships/{dealershipId}/availability?serviceTypeId=…` to follow CLAUDE.md §2 nesting? | Yes, in its own spec **after** Spec05, together with an `AvailabilityQuery` request object (D4) and a deprecation window for the old path. It changes the public contract, DLD §3 and Postman. |
| Q2 | `common-logging` aspect pointcut matches `@Service` on the impl only. Should it also match `@Repository` for SQL timing? | Not in Spec05 (adds log volume). Decide with observability (DLD §12). |
| Q3 | Should `SlotCalculator` become a Spring bean behind an interface? | No. It is pure and static; an interface adds nothing to test it. CLAUDE.md §1 already allows pure helpers. |

---

## 12. Out of scope

- Any business change: new endpoints, booking write path, cleanup job logic, rate limiting.
- Changing SQL, indexes, migrations, Redis keys or TTLs.
- `common-logging` code (Javadoc only).
- Path change of the availability endpoint (Q1).
