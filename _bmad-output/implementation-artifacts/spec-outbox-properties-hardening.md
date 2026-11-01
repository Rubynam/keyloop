---
title: 'Outbox settings hardening — required counts, cleanup validation, upper bounds'
type: 'bugfix'
created: '2026-11-01'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'efbb5b1f5a69b56a86fae3262c26de98249733a1'
context:
  - '{project-root}/CLAUDE.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Three gaps left by `spec-outbox-relay-properties-validation` (its last three deferred-work entries, B1/B2/B3): a missing `max-retries` or `batch-size` binds to `0` (primitive `int`) and `max-retries: 0` passes, so retries turn off with no error; `OutboxCleanupProperties` has no validation, so `failed-retention: 0s` would delete FAILED rows at once; relay settings have no upper bound, so `max-retries=2147483647` overflows `maxRetries() + 1` (every row FAILED) and huge durations overflow `Duration.toMillis()`.

**Approach:** Make the two relay counts `Integer` + `@NotNull`, mark `OutboxCleanupProperties` `@Validated` with the standard constraints, and add `@Max` / `@DurationMax` on relay settings with the limits held in `RelayConstants`. Startup fails and names the property, same as the existing relay checks.

## Boundaries & Constraints

**Always:**
- Relay limits (inclusive): `batch-size` ≤ 1000, `max-retries` ≤ 100, `poll-delay` ≤ 1h, `retry-delay` ≤ 1h, `version-key-ttl` ≤ 30d.
- Cleanup rules: `cron` `@NotBlank`; `processed-retention`, `failed-retention`, `batch-metadata-retention` `@NotNull @DurationMin(millis = 1)`. Validated even when `cleanup.enabled=false` (the record is always bound, like the relay one).
- Limit values are `public static final` constants in `constant/outbox/RelayConstants` (CLAUDE.md §3), not literals in the annotations.
- Shipped `application.yml` values stay unchanged and valid; existing tests stay green, including ITs using `poll-delay=1h`.
- Callers (`OutboxRelay`, `OutboxRelayServiceImpl`) keep compiling and behaving the same.
- Standard JSR-380 path only; failure message comes from Spring Boot's `BindValidationException`.

**Never:**
- No upper bounds on cleanup retentions (the cleanup job is still a placeholder; nothing converts them yet).
- No cron syntax check beyond `@NotBlank`, and no cross-module check (e.g. `version-key-ttl` vs server-api cache TTL).
- No change to relay loop logic, SQL, or `application.yml`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Defaults | shipped relay + cleanup values | context starts | N/A |
| Count missing | `batch-size` or `max-retries` key removed | startup fails, names `batchSize` / `maxRetries` | binding error |
| Count at max | `batch-size=1000`, `max-retries=100` | context starts | N/A |
| Count over max | `batch-size=1001`, `max-retries=101`, `max-retries=2147483647` | startup fails, names the property | binding error |
| Duration at max | `poll-delay=1h`, `retry-delay=1h`, `version-key-ttl=30d` | context starts | N/A |
| Duration over max | max + 1ms on each relay duration | startup fails, names the property | binding error |
| Cleanup cron blank / missing | `cron=` or key removed | startup fails, names `cron` | binding error |
| Cleanup retention zero / missing | `0s` or key removed, each of the three | startup fails, names the property | binding error |
| Cleanup disabled, bad value | `enabled=false`, `failed-retention=0s` | startup fails, names `failedRetention` | binding error |

</frozen-after-approval>

## Code Map

- `scheduler-batch/src/main/java/com/keyloop/scheduler/batch/config/outbox/OutboxRelayProperties.java` -- `@Validated` record; `int batchSize` / `int maxRetries` become `Integer`. Its Javadoc example lists the rules; extend it.
- `.../config/outbox/OutboxCleanupProperties.java` -- record with no validation, one-line Javadoc; bound via `@ConfigurationPropertiesScan` on `SchedulerBatchApplication`. No code reads it yet (`job/outbox/OutboxCleanupJobConfig` is a placeholder).
- `.../constant/outbox/RelayConstants.java` -- `@NoArgsConstructor(PRIVATE)` constants class; add limits next to the relay property keys. `@DurationMax` attributes are `long` (`days`, `hours`, ...); `@Max.value` is `long`.
- `.../schedule/OutboxRelay.java:42` -- `tick() == properties.batchSize()`: `int == Integer` unboxes, stays a value compare. Keep `tick()` returning `int`.
- `.../service/outbox/impl/OutboxRelayServiceImpl.java:81,111,170` -- arithmetic/compare on the counts; unboxing, no change needed.
- `.../repository/outbox/impl/JdbcOutboxEventRepository.java:55,92` -- `delay.toMillis()`; read only.
- `scheduler-batch/src/main/resources/application.yml:37-50` -- relay and cleanup values. Read only.
- `scheduler-batch/src/test/java/com/keyloop/scheduler/batch/config/outbox/OutboxRelayPropertiesTest.java` -- `ApplicationContextRunner` + `ValidationAutoConfiguration`, `Locale.ENGLISH` pinned, `withDefaults(...)`, `assertFailsNaming(...)`. Reuse the pattern.
- `.../SchedulerBatchApplicationTest.java:47` -- `isEqualTo(3)` on `maxRetries()`; works with `Integer`.

## Tasks & Acceptance

**Execution:**
- [x] `.../constant/outbox/RelayConstants.java` -- add `MAX_BATCH_SIZE`, `MAX_RETRIES_LIMIT`, `MAX_POLL_DELAY_HOURS`, `MAX_RETRY_DELAY_HOURS`, `MAX_VERSION_KEY_TTL_DAYS`, each with a short "why" comment; mention limits in the class Javadoc -- §3.
- [x] `.../config/outbox/OutboxRelayProperties.java` -- `@NotNull @Positive @Max Integer batchSize`, `@NotNull @PositiveOrZero @Max Integer maxRetries`, `@DurationMax` on the three durations; update Javadoc example -- B1, B3.
- [x] `.../config/outbox/OutboxCleanupProperties.java` -- `@Validated` + constraints above; two-part Javadoc -- B2.
- [x] `.../config/outbox/OutboxRelayPropertiesTest.java` -- add the relay rows of the matrix; build values from the constants.
- [x] `.../config/outbox/OutboxCleanupPropertiesTest.java` -- new, same pattern, cleanup rows of the matrix; share the failure-message helper with the relay test instead of copying it.
- [x] `_bmad-output/implementation-artifacts/deferred-work.md` -- add one `resolution:` line under each of the last three entries; edit no existing line.

**Acceptance Criteria:**
- Given the shipped `application.yml`, when `SchedulerBatchApplicationTest` runs, then the context starts with `maxRetries() == 3`.
- Given the change, when `./gradlew :scheduler-batch:test` runs, then all tests pass with no edits to existing IT files.

## Implementation Notes

- Shared test helpers moved to package-private `PropertiesTestSupport` (`withDefaults`, `without`, `assertFailsNaming`); locale pin stays per test class.
- `OutboxRelayIT.twoInstancesNeverProcessTheSameRow` is flaky (`version == 0` for one date at line 207): 1/5 runs on this branch, 3/8 on baseline `efbb5b1` — pre-existing, deferred.

## Spec Change Log

## Review Triage Log

Pass 1 (blind = Blind Hunter, edge = Edge Case Hunter, gap = Verification Gap).

| # | Layer | Finding | Verdict | Evidence | Route |
|---|-------|---------|---------|----------|-------|
| 1 | edge, blind | 1ms retention floor still deletes rows almost at once | low | Real, but the frozen Always fixes `@DurationMin(millis = 1)`; Javadoc says "zero", which is accurate | reject (intent) |
| 2 | edge, blind | `cron` only `@NotBlank`, bad syntax starts | medium | Real; frozen Never excludes a syntax check; pre-existing | defer |
| 3 | edge, blind | No upper bound on cleanup retentions | maybe-false (medium if true) | Nothing converts retentions yet (`OutboxCleanupJobConfig` is a placeholder); frozen Never excludes; settle when the job is written | defer |
| 4 | edge, blind | `version-key-ttl` floor below server-api cache TTL serves stale data | medium | Real; frozen Never excludes cross-module checks; pre-existing | defer |
| 5 | edge | `enabled` primitive `boolean` binds missing to `false` | medium | Real, same class as B1; pre-existing, not in intent | defer |
| 6 | edge | `without()` typo is a silent no-op | low | A typo leaves the key set, so the expected-failure test fails loudly; defaults always contain `=` | reject |
| 7 | edge | Readers may think explicit `max-retries: 0` is rejected | false | Intent names only the missing key; `zeroMaxRetriesStarts` documents 0 as valid | reject |
| 8 | blind | 100 retries × 1h delay ≈ 4 days, unchecked | low | No overflow or wrong behaviour; combination is a tuning choice | reject |
| 9 | blind | Limits not commented in `application.yml` | low | Frozen Never: no `application.yml` change | reject |
| 10 | blind | Limits typed `long`, force `(int)` casts | low | Direct correction | patch |
| 11 | blind | `MAX_RETRIES_LIMIT` naming uneven | false | `MAX_RETRIES` would read as the setting itself; name is deliberate | reject |
| 12 | blind | `RelayConstants` "do not change a character" now covers tunable limits | low | Javadoc contradicts the limits' purpose; direct rewording | patch |
| 13 | blind | No test binds the real `application.yml` | false | `SchedulerBatchApplicationTest` starts on the shipped yml and asserts `maxRetries() == 3` | reject |
| 14 | blind | `disabledStillValidated` duplicates other cases; no `enabled=true` case | low | Redundant but documents a matrix row; harmless | reject |
| 15 | blind, gap | Failure asserts check only the field name; `missingBatchSizeFails` passes on old `int` | medium | gap layer confirmed: 0 fails `@Positive` with the same field name, so B1 is not proven for batch-size | patch |
| 16 | blind | `max-retries=0`, negative durations, 1ms cleanup edge untested | false / low | `zeroMaxRetriesStarts` exists; negatives fail `@DurationMin` already tested at 0 | reject |
| 17 | blind | Locale pin duplicated and JVM-global | low | 10 lines, no parallel execution configured; pattern required by the brief | reject |
| 18 | blind | Spec reference mid-paragraph, misses this spec | low | CLAUDE.md §4 wants the reference at the end of Part 1; direct correction | patch |
| 19 | blind | Cleanup Javadoc implies cron syntax is checked | low | Wording fix only | patch |
| 20 | gap | Five ITs use `poll-delay=1h`, exactly the inclusive limit | low | Deliberate (Design Notes); `@DurationMax` is inclusive by default | reject |

## Design Notes

Limits are guard rails against typos and overflow, not tuning values, so they are constants, not `application.yml` keys. Chosen as ~10× the shipped value or more: 1000 rows per tick (default 100), 100 retries (default 3; 2s × 100 ≈ 3 min), 1h delays (ITs use `poll-delay=1h`; longer means "turn it off", which `enabled` does), 30d version-key TTL (default 2d).

```java
@NotNull @Positive @Max(RelayConstants.MAX_BATCH_SIZE) Integer batchSize,
@NotNull @DurationMin(millis = 1) @DurationMax(hours = RelayConstants.MAX_POLL_DELAY_HOURS) Duration pollDelay,
```

## Verification

**Commands:**
- `./gradlew :scheduler-batch:test` -- expected: BUILD SUCCESSFUL, new property tests included.
