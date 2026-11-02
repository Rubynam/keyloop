---
title: 'Outbox relay Task 3 — validate relay settings at startup'
type: 'bugfix'
created: '2026-11-01'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '3e2d86bfc9a5135ecfe449781d15464c757dd736'
context:
  - '{project-root}/CLAUDE.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `OutboxRelayProperties` has no validation. `batch-size: 0` (or negative) makes `OutboxRelay.relay()` spin forever (`while (tick() == batchSize)` with `0 == 0`), and `retry-delay: 0s` / `max-retries: -1` silently break the retry policy (story-outbox-relay Task 3, deferred-work entries E3 and the Spec05 "batch-size positive" entry).

**Approach:** Add Bean Validation to `scheduler-batch` and mark `OutboxRelayProperties` `@Validated` so bad values stop the app at startup with a clear binding error. Prove with tests that the retry path really follows `max-retries` and `retry-delay` from config, not hard-coded values.

## Boundaries & Constraints

**Always:**
- Rules per property: `batch-size` `@Positive`; `max-retries` `@PositiveOrZero`; `poll-delay`, `retry-delay`, `version-key-ttl` `@DurationMin(millis = 1)` and `@NotNull`.
- Startup failure must name the property and the rule (Spring Boot's `BindValidationException` output is enough — no custom message code).
- Default values in `application.yml` stay unchanged and valid.
- Existing tests stay green unchanged, `OutboxRelayRedisDownIT` included.
- CLAUDE.md §3/§4: no new magic numbers in main code; short two-part Javadoc on the record.

**Never:**
- Do not validate `OutboxCleanupProperties` here (not in Task 3).
- Do not change `OutboxRelay.relay()` loop logic or the V2 "stopped tick" issue (separate deferred entry).
- Do not add a `@Configuration` validator bean or hand-written checks in the service; use the standard JSR-380 path only.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Defaults | shipped `application.yml` | context starts | N/A |
| batch-size zero | `batch-size: 0` | startup fails, message names `batchSize` / "greater than 0" | binding error |
| max-retries negative | `max-retries: -1` | startup fails, names `maxRetries` | binding error |
| max-retries zero | `max-retries: 0` | context starts (one try, no retry) | N/A |
| retry-delay zero | `retry-delay: 0s` | startup fails, names `retryDelay` | binding error |
| poll-delay / version-key-ttl zero | `0s` | startup fails, names the property | binding error |
| duration missing | property removed | startup fails (`@NotNull`) | binding error |

</frozen-after-approval>

## Code Map

- `scheduler-batch/build.gradle.kts` -- add `implementation("org.springframework.boot:spring-boot-starter-validation")` (same line as `server-api/build.gradle.kts:8`).
- `scheduler-batch/src/main/java/com/keyloop/scheduler/batch/config/outbox/OutboxRelayProperties.java` -- record bound via `@ConfigurationPropertiesScan` on `SchedulerBatchApplication`; add `@Validated` + constraint annotations on components. `@DurationMin` is `org.hibernate.validator.constraints.time.DurationMin`.
- `scheduler-batch/src/main/resources/application.yml:37-44` -- relay defaults (`batch-size: 100`, `max-retries: 3`, `retry-delay: 2s`, ...). Read only.
- `scheduler-batch/src/test/java/com/keyloop/scheduler/batch/service/outbox/impl/OutboxRelayCrashIT.java` -- reuse its `CrashingStore` / `CrashingStoreConfig` (package-private, same package) and the `makeDue` trick for the crash path of AC3.2. Do not edit.
- `.../service/outbox/impl/OutboxRelayRedisDownIT.java` -- pattern for Redis-down retries (stops its own container, `@DirtiesContext(AFTER_CLASS)`, checks `next_attempt_at` window). Do not edit.
- `.../support/IntegrationTest.java`, `OutboxRows`, `LogCapture` -- shared test support to reuse.

## Tasks & Acceptance

**Execution:**
- [x] `scheduler-batch/build.gradle.kts` -- add validation starter -- enables JSR-380 for `@ConfigurationProperties`.
- [x] `.../config/outbox/OutboxRelayProperties.java` -- `@Validated` + constraints from Boundaries; update Javadoc (Part 1 + a small "bad value → startup fails" example) -- fail fast instead of a spinning relay.
- [x] `scheduler-batch/src/test/java/com/keyloop/scheduler/batch/config/outbox/OutboxRelayPropertiesTest.java` -- new, no containers: `ApplicationContextRunner` with `ValidationAutoConfiguration` + `@EnableConfigurationProperties(OutboxRelayProperties.class)`, one case per matrix row -- AC3.1.
- [x] `.../service/outbox/impl/OutboxRelayRetrySettingsCrashIT.java` -- new; `max-retries=1`, `retry-delay=500ms`, `poll-delay=1h`; import `OutboxRelayCrashIT.CrashingStoreConfig`; crashing row: 2 tries, `next_attempt_at` ≈ +500 ms after try 1, FAILED at the next claim -- AC3.2 crash path.
- [x] `.../service/outbox/impl/OutboxRelayRetrySettingsRedisDownIT.java` -- new; same settings, Redis container stopped, `@DirtiesContext(AFTER_CLASS)`; 2 tries 500 ms apart, then FAILED with the "after 2 attempts" ERROR line -- AC3.2 Redis-down path.

**Acceptance Criteria:**
- Given `max-retries: 1` and `retry-delay: 500ms`, when Redis is down, then the row gets 2 tries, the second `next_attempt_at` is 500 ms after try 1, and the row is FAILED after 2 attempts.
- Given the same settings, when the store throws an `Error` during the work, then the row is FAILED at the claim after 2 started tries; no 3rd try runs.
- Given a bad relay value, when the app starts, then the context fails and the error text names the property.

## Implementation Notes

## Spec Change Log

## Review Triage Log

Pass 1 (blind-hunter B, edge-case-hunter E, verification-gap V).

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| B1 | Missing `max-retries` binds to 0 (primitive `int`) and passes `@PositiveOrZero` | low | True: record constructor binding defaults a missing `int` to 0, so retries go off silently. Same before this change (pre-existing binding default); shipped yml sets it. | defer |
| B2 | `OutboxCleanupProperties` not validated (0 `failed-retention` deletes FAILED rows at once) | medium | Real and pre-existing; intent's Never excludes it from Task 3. | defer |
| B3/E1/E2 | No upper bounds: `max-retries=Integer.MAX_VALUE` overflows `maxRetries()+1` at `OutboxRelayServiceImpl:111` (every row FAILED); huge durations overflow `toMillis()` at `JdbcOutboxEventRepository:55` | low | Real but needs absurd values; overflow existed before validation. Shared root cause: no upper bound. | defer |
| B4 | Test `DEFAULTS` is a hand copy of `application.yml`, so shipped config start is unproven | false | `SchedulerBatchApplicationTest` boots the full context on the shipped yml and asserts `maxRetries=3`, `retryDelay=2s` (V). |  reject |
| B5 | No tests for negative / sub-ms durations, missing `batch-size`; some tests check only the name | low | Library behaviour of `@DurationMin`/`@Positive`; every matrix row already covered. | reject |
| B6 | `missingDurationsFail` drops keys by substring (`delay`/`ttl`) | low | True; direct fix (list keys by name). | patch |
| B7 | `@Scheduled` reads `poll-delay` via placeholder, may parse differently from the record | false | Both read `scheduler.outbox.relay.poll-delay` and both default a bare number to ms; pre-existing wiring (`RelayConstants.RELAY_POLL_DELAY`). | reject |
| B8/E5 | ITs never show a tick before 500 ms leaves the row alone | low | The gate is the same SQL already proven by `OutboxRelayRedisDownIT` (2 s); `next_attempt_at` window proves the configured value. A 500 ms early-tick check would be flaky. | reject |
| B9 | Redis-down IT hard-codes fixture UUID/date from `OutboxRows.sample` | low | Same pattern as existing `OutboxRelayRedisDownIT`; cosmetic. | reject |
| B10 | Crash helpers live in `OutboxRelayCrashIT`, now shared by two ITs | low | Spec Code Map chose reuse; moving them is a refactor. | reject |
| B11a | Javadoc example omits `poll-delay` / `version-key-ttl` | low | True; one-line fix. | patch |
| B11b | "must be greater than 0" literal repeated in tests | low | Test assertions; §3 targets main-code logic. | reject |
| B11c | No ArchUnit rule forcing `@Validated` on properties records | low | New rule, not a defect of this change. | reject |
| E3 | Validation runs even when `enabled=false` | false | Intent: bad values stop the app; failing on a bad value for a disabled relay is intended, not a defect. | reject |
| E4 | `"must be greater than 0"` assertion breaks under a non-English default locale | low | True: Hibernate Validator interpolates with `Locale.getDefault()`; direct fix (pin `Locale.ENGLISH` in the test). | patch |
| V | No verification gaps | — | Each annotation, the dependency, shipped yml and configured retry values are pinned by tests. | — |

## Verification

**Commands:**
- `./gradlew :scheduler-batch:test` -- expected: all tests pass, including the 3 new classes and unchanged `OutboxRelayRedisDownIT` / `OutboxRelayCrashIT`.
- `./gradlew :scheduler-batch:test --tests '*ArchitectureTest'` -- expected: pass (no layering change).
