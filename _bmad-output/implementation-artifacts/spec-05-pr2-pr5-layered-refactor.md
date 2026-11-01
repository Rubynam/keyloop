---
title: 'Spec05 PR2–PR5 — availability, booking and scheduler-batch behind interfaces, plus ArchUnit'
type: 'refactor'
created: '2026-11-01'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '245ba7f4d48a0540fbc0b888ccecb6305a06bc73'
context: ['{project-root}/planing/Spec05.md', '{project-root}/CLAUDE.md', '{project-root}/planing/Spec04.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** After Spec05 PR1, `server-api/availability`, `server-api/booking` and all of `scheduler-batch` still break CLAUDE.md §1–§4: concrete service/repository/cache classes with no interfaces, SQL and Redis in the `@Scheduled` relay, magic literals, one-part Javadoc, and nothing stops the rules from breaking again.

**Approach:** Deliver Spec05 §9 PR2 (availability), PR3 (booking), PR4 (scheduler-batch) and PR5 (ArchUnit ARC-01…08, CLAUDE.md layout RF-14, RF-11 grep) in one run, with no behaviour change. Target packages, names and constants follow Spec05 §4–§8. Use `git mv`.

## Boundaries & Constraints

**Always:** HTTP contract, SQL text, Flyway, `application.yml` keys, Redis key layout, relay retry policy (Spec04 §4) and every exception/log message stay byte-equal. One constructor per class, constructor injection. Javadoc on interfaces (two-part, CLAUDE.md §4), existing facts kept (RF-13). Test packages mirror main packages. Each PR slice is green on `./gradlew test` by itself (P3).

**Never:** change `common-logging` code; change the availability path (D3/Q1); bind a query object (D4); delete a test or change an assertion beyond imports, class names, constructor calls and logger class (TST-01); rename Redis keys to `avail:slots:` (Spec05 §6 typo — code uses `avail:`).

**Decisions (human, 2026-11-01):**
- Delivery: one branch, 4 commits (one per PR slice PR2/PR3/PR4/PR5, each green alone), one GitHub PR.
- `OutboxRelayServiceImpl` and `HeartbeatServiceImpl` use `@Service`. The extra INFO line per `tick()` from `MethodLoggingAspect` is accepted.
- Spec kept whole (~2,000 tokens) to cover PR2–PR5 in one run.

</frozen-after-approval>

## Code Map

server-api (`com.keyloop.scheduler.api`):
- `availability/AvailabilityController` → `controller/availability/`; already uses `ApiPaths`; depends on `AvailabilityService` interface.
- `availability/AvailabilityService` → interface `service/availability/AvailabilityService` (`find(...)`) + `service/availability/impl/AvailabilityServiceImpl`. Drop package-private `(repo, clock)` ctor (D7/RF-04); `validateRange` stays package-private static. `MAX_DAYS/MIN_YEAR/MAX_YEAR` and all three range messages + invalid-zone message → `constant/availability/AvailabilityConstants`.
- `availability/AvailabilityRepository` → interface (6 public methods) + `repository/availability/impl/JdbcAvailabilityRepository`; nested `Dealership`, `Booking` → `model/availability/`.
- `availability/AvailabilityCache` → interface `cache/availability/AvailabilityCache` with `lookup(...)` and nested interface `Lookup { slots(loader) }`; impl `cache/availability/impl/RedisAvailabilityCache` (inner `RedisLookup`). Ctor param name `availabilityLockReleaseScript` must stay (bean name). Key helpers stay package-private static on the impl; prefixes `avail:ver:`, `avail:`, `avail:lock:` and `FAIL_OPEN_EVENT` → `AvailabilityConstants`. Remove `disabled()` and `AvailabilityCacheProperties.off()` if nothing else uses them.
- `AvailabilityCacheProperties`, `AvailabilityConfig` → `config/availability/`. Lua text block → `src/main/resources/redis/release-lock.lua` via `ClassPathResource` (RF-10); bean name `availabilityLockReleaseScript` unchanged.
- `AvailabilityResponse` → `dto/availability/`; `InvalidRangeException` → `exception/availability/` (update `web/ApiExceptionHandler` import).
- `SlotCalculator` → `service/availability/impl/`; `STEP_MINUTES` → constants; `Interval` → `model/availability/Interval` (`overlaps` becomes public).
- `booking/CandidateSelector` → interface `service/booking/CandidateSelector` (`select`) + `impl/CandidateSelectorImpl`; `MAX_PAIRS` → `constant/booking/BookingConstants`; duplicate zone message → `AvailabilityConstants.INVALID_TIME_ZONE`.
- `booking/CandidateRepository` → interface + `repository/booking/impl/JdbcCandidateRepository`; `CandidateResult` → `model/booking/` (factories become public; Javadoc `{@value}` → `BookingConstants#MAX_PAIRS`).
- Tests: `AvailabilityCacheIT`/`AvailabilityFailOpenIT` → `cache.availability.impl` (LogCapture → `RedisAvailabilityCache.class`); `AvailabilityServiceTest`, `SlotCalculatorTest` → `service.availability.impl`; `AvailabilityRepositoryIT` → `repository.availability.impl`; `AvailabilityControllerTest` → `controller.availability`; `CandidateSelectorIT` → `service.booking.impl`. Replace `AvailabilityCache.disabled()` with a pass-through fake in `support/` that calls the loader exactly as `disabled()` did.

scheduler-batch (`com.keyloop.scheduler.batch`):
- `outbox/OutboxRelay` (`@Component`, conditional, ctor `JdbcClient, StringRedisTemplate, PlatformTransactionManager, JsonMapper, OutboxRelayProperties`) → `schedule/OutboxRelay`: keeps `@ConditionalOnProperty` + `@Scheduled relay()` loop `while (relayService.tick() == batchSize)`.
- `service/outbox/OutboxRelayService` (`int tick()`) + `impl/OutboxRelayServiceImpl`: `TransactionTemplate`, per-row flow, retry/fail policy, `parse` + `Payload` (package-private), `logWithEventId`, all log lines (logger class changes).
- `repository/outbox/OutboxEventRepository` + `impl/JdbcOutboxEventRepository`: SKIP LOCKED select, `markProcessed`, retry update, `markFailed` — SQL byte-equal; `Row` → `model/outbox/OutboxEvent`.
- `store/outbox/AvailabilityVersionStore` + `impl/RedisAvailabilityVersionStore`: `INCR` + `EXPIRE` with `versionKeyTtl`.
- `InvalidPayloadException` → `exception/outbox/`; payload messages, `avail:ver:` prefix → `constant/outbox/RelayConstants`.
- `*Properties` → `config/outbox/`.
- `job/HeartbeatTasklet` → `job/heartbeat/`, calls `HeartbeatService.check()` only; `service/heartbeat/HeartbeatService` + impl (keeps log line `heartbeat ok: postgres={} redis={}`); `repository/heartbeat/HeartbeatProbe` + `impl/JdbcRedisHeartbeatProbe` (`SELECT 1`, `PING`).
- `HeartbeatJobConfig` → `job/heartbeat/`, `OutboxCleanupJobConfig` → `job/outbox/`; names → `constant/JobNames`; `"run.at"`, `PT25S`, `PT10S`, `PT5M` → `constant/SchedulingConstants` (`JobScheduler`, `SchedulingConfig`).
- Tests: `OutboxRelayIT`, `OutboxRelayRedisDownIT` → `service.outbox.impl`, autowire `OutboxRelayService`; `OutboxRelayDisabledTest` still checks no `OutboxRelay` bean; `HeartbeatTaskletTest` split into tasklet (mock service) + probe test (keeps `SELECT 1` stub); `SchedulerBatchApplicationTest` imports updated.

Build: version catalog `gradle/libs.versions.toml`; Java toolchain 25. Spec05 names ArchUnit 1.3.0, which may not read Java 25 class files — use the lowest ArchUnit release that does, proved by the test running.

## Tasks & Acceptance

**Execution:**
- [x] Branch `refactor/spec05-pr2-pr5` from `main` @ `245ba7f4d48a0540fbc0b888ccecb6305a06bc73`; one commit per slice below.
- [x] PR2 slice — `server-api` availability moves, interfaces, constants, Lua resource, Javadoc, tests -- Code Map above -- `./gradlew :server-api:test` green.
- [x] PR3 slice — `server-api` booking -- Code Map -- `:server-api:test` green, `CandidateSelectorIT` unchanged.
- [x] PR4 slice — `scheduler-batch` split -- Code Map -- `:scheduler-batch:test` green, relay ITs unchanged.
- [x] PR5 slice — `libs.versions.toml` + both `build.gradle.kts` (archunit-junit5), `ArchitectureTest` in each app (ARC-01…08, Spec05 §7, main classes only), `CLAUDE.md` §1 layout (RF-14) -- `./gradlew test` green.
- [x] Run RF-11 grep; fix hits.

**Acceptance Criteria:**
- Given the refactor, when `./gradlew test` runs, then all modules pass, including every Testcontainers IT and both `ArchitectureTest`s.
- Given a controller/scheduler that imports a `*Impl` or `JdbcClient`, when `ArchitectureTest` runs, then it fails.
- Given the RF-11 grep, when run, then it prints nothing.
- Given `git diff main --stat -M`, when reviewed, then no SQL, migration, `application.yml` key or Redis key text changed.

## Implementation Notes

- Commits on `refactor/spec05-pr2-pr5`: f2db205 (PR2), be7d267 (PR3), a419783 (PR4), 065d79d (PR5). Not pushed; no GitHub PR opened yet.
- ArchUnit **1.3.2**: 1.3.0, 1.3.1 and 1.4.0 import zero Java 25 classes (every rule "failed to check any classes"); 1.3.2 and 1.4.1 work.
- Spring Data Redis 4.1 has no `DefaultRedisScript(Resource, Class)` ctor; RF-10 uses `RedisScript.of(new ClassPathResource(...), Long.class)`.
- Year-bounds message also moved: `AvailabilityConstants.RANGE_YEARS_OUT_OF_BOUNDS`. Cache JSON field names, fail-open event description and cache error texts are constants too.
- `OutboxRelayIT` calls package-private `parse` through `AopTestUtils.getTargetObject(relay)` (field is `OutboxRelayService`); call sites changed from `relay.parse(x)` to `parse(x)`, assertions unchanged.
- `OutboxRelayDisabledTest` → `schedule`, `OutboxPayloadContractTest` → `service.outbox.impl` (mirror main).
- Property keys used in `@ConditionalOnProperty` / `@Scheduled` moved to `SchedulingConstants` / `RelayConstants` (RF-11).
- RF-11 also hit PR1's `PingController` (`"ok"` + Javadoc JSON): moved to `constant/ping/PingConstants`.
- ARC-04 `@RestController` rule uses `allowEmptyShould(true)` (scheduler-batch has none). ARC-01/02 layer and impl patterns are prefixed with the module package so Spring's `..repository..`/`..job..` packages do not match.

## Spec Change Log

## Review Triage Log

- [V] HeartbeatServiceImpl failure path untested after split — medium — pre-verified gap: no test builds `HeartbeatServiceImpl`; swallowing a probe error would pass every test. Patch (+ Blind "HeartbeatServiceImpl has no unit test", same root).
- [V] `scheduler.availability.cache.enabled=false` lost its only coverage when `disabled()` was removed — medium — pre-verified: no test runs `RedisAvailabilityCache` with `enabled=false`. Patch.
- [B] server-api `ArchitectureTest` copies batch comment + `allowEmptyShould(true)` on the `@RestController` rule — low — real: server-api has controllers, so an empty match should fail there. Patch.
- [B][E] ARC-03 misses `JdbcOperations`, `RedisOperations`, `RedisConnectionFactory`, `DataSource` — low — real: a service injecting one passes; only `SchedulingConfig` (exempt) uses `DataSource` today. Patch (direct additions).
- [E] ARC-06 misses `@Autowired` setter/method injection that CLAUDE.md §1 forbids — low — real. Patch (one `noMethods()` rule).
- [B][E] `@Qualifier(JobNames.HEARTBEAT_JOB)` matches only via method name `heartbeatJob` — low — real: renaming the constant breaks startup. Patch: `@Bean(JobNames.HEARTBEAT_JOB)` / `@Bean(JobNames.HEARTBEAT_STEP)`.
- [B] Cache port signature uses HTTP DTO `AvailabilityResponse.Slot` — low — real but pre-existing (`AvailabilityCache` used `Slot` at 245ba7f); fix needs a model type + mapping. Defer.
- [B][E] `avail:ver:` prefix defined in two modules, no contract test — medium — pre-existing (both literals existed at 245ba7f). Defer.
- [B][E] `batch-size: 0` makes `relay()` loop forever — medium — real but pre-existing (same loop at 245ba7f, no validation). Defer.
- [B] CLAUDE.md layout tree omits `constant/ping/PingConstants` and `resources/redis/release-lock.lua` — low — real; fix edits agent-context file. Defer.
- [B] ARC-08 allows packages an app lacks; two ArchitectureTests not shared — false — ARC-08 package list is exactly Spec05 §7; no stray package exists.
- [B] `@Component` on `RedisAvailabilityCache` / `RedisAvailabilityVersionStore` vs `@Repository` — false — CLAUDE.md §1 and ARC-04 do not constrain `@Component`; `RedisAvailabilityCache` was `@Component` before; no named harm.
- [B] `HeartbeatProbe` (touches Redis) lives in `repository/`, not `store/` — false — placement is Spec05 §4.2/§5 exactly.
- [B] `parse` should be a pure `OutboxPayloadParser` helper; AopTestUtils workaround — false — no defect; test reaches the package-private method correctly through the aspect proxy.
- [B] Log patterns only partly moved to constants — false — RF-11 excludes log patterns; no text changed.
- [B] Property-key constants split between `SchedulingConstants` / `RelayConstants` — false — no named breakage; prefix duplication with `@ConfigurationProperties` pre-exists as literals.
- [B] Tasklet test names mention Postgres/Redis; no OutboxRelay loop unit test — false — TST-01 keeps names; loop is unchanged and covered by `OutboxRelayIT`.
- [B] Impl classes lack two-part Javadoc; ping example `{ status: ok }` — false — RF-12 puts Javadoc on interfaces; example text is a comment, wire body pinned by `PingControllerTest`.
- [B] `zoneOf` duplicated; booking imports `AvailabilityConstants.INVALID_TIME_ZONE` — false — Code Map prescribes it; ARC-07 passes (booking→availability only).
- [B] ARC-02/ARC-05 escapable via `internal` folders — false — patterns match Spec05 §7; ARC-08 + ARC-05 cover today's tree; speculative.
- [E] Lua resource read lazily; missing file swallowed as fail-open — false — resource is in the jar and pinned by `AvailabilityCacheIT.luaReleaseDeletesOnlyTheOwnersLock`; a load error is `ScriptingException`, not the `DataAccessException` the fail-open path catches, so it would fail loudly.
- [E] "relay ITs unchanged" claim while ITs were edited — false — TST-01 allows import/class/logger-class edits; no assertion changed.
- [E] Logger categories changed; relay beans load when relay disabled — false — D6 accepts the name change; no logback/yml config names these loggers; extra beans do no work.

## Verification

**Commands:**
- `./gradlew test` -- expected: BUILD SUCCESSFUL, 0 failures.
- `grep -rnE '"[^"]{2,}"' --include=*.java */src/main/java | grep -E '/(controller|service|store)/' | grep -v 'log\.'` -- expected: no output.
- `git diff main -M --stat` -- expected: moves show as renames.
