---
title: 'Outbox relay: claim one row per try, re-lock in the work transaction'
type: 'bugfix'
created: '2026-11-01'
status: 'done'
baseline_commit: '6c94dcdc14ca5bab8993cfb39d47f24fa1333b88'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/story-outbox-relay.md'
  - '{project-root}/CLAUDE.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The relay saves `attempts` in the same transaction that bumps Redis and marks the row. If the JVM dies there, the try is rolled back and never counted, so a payload that crashes the worker is retried forever (story OP2).

**Approach:** Story Tasks 1, 2, 4, 5 only. Per row: TX A locks the next due row and counts the try (or fails it when exhausted) and commits; TX B re-locks the row by `id + claimed attempts`, then does the existing work (parse → bump → mark processed, or the §4.3 failure path). Deferred (see `deferred-work.md`): Task 3 validation, AC-F.3 non-integer key, pipelined `bump(List)`, Task 6, Task 7.

## Boundaries & Constraints

**Always:**
- Crashes and Redis errors share one limit: `max-retries` / `retry-delay` from `OutboxRelayProperties`; no new property, no literal `3` or `2s`.
- In TX B, Redis bump runs before `markProcessed` (at-least-once, story §6).
- Use the claimed `attempts` as is, never `+ 1`. TX B writes never change `attempts`.
- PostgreSQL `now()` for all timestamps. CLAUDE.md §1–§4.
- `OutboxRelayRedisDownIT` stays green and unchanged. `OutboxRelay.relay()` unchanged.

**Never:**
- No migration, no server-api change, nothing from the deferred list.
- Do not catch `Error`: it stands for a JVM crash and must leave `tick()`.
- No extra guard `WHERE` on TX B writes; the re-lock protects them.

## I/O & Edge-Case Matrix

`attempts` = value after the claim; default max-retries 3.

| Scenario | State | Expected | Log |
|---|---|---|---|
| Happy path | due, valid, Redis up | PROCESSED, `attempts = 1` | — |
| Bad payload | invalid JSON/fields | FAILED at once, `attempts = 1` | `INVALID_PAYLOAD_LOG` (unchanged) |
| Redis/Runtime error, try 1–3 | Redis down | PENDING, `next_attempt_at = now()+retry-delay`; tick stops | WARN (unchanged) |
| Redis/Runtime error, try 4 | `attempts = 4` | FAILED same tick | `FAILED_AFTER_RETRIES_LOG` (unchanged) |
| Crash in work | `Error` in TX B | TX B rolls back, try stays counted, `Error` leaves `tick()` | — |
| Exhausted at claim | `attempts ≥ max-retries + 1` | FAILED, `last_error = CLAIM_EXHAUSTED_ERROR`, no work, counted in result | ERROR `CLAIM_EXHAUSTED_LOG` via `logWithEventId` |
| Relock miss | row locked elsewhere, or `attempts`/terminal state changed | TX B rolled back, row untouched, next row | WARN `RELOCK_SKIPPED_LOG` |

</frozen-after-approval>

## Code Map

Base `scheduler-batch/src/main/java/com/keyloop/scheduler/batch/`.

- `service/outbox/impl/OutboxRelayServiceImpl.java` -- today one `TransactionTemplate` around `lockDueBatch`; `markRetryOrFailed`/`markInvalid` use `row.attempts() + 1` (remove). Keep `parse`, `Payload`, `logWithEventId`, per-date `bump` loop.
- `repository/outbox/OutboxEventRepository.java` + `impl/JdbcOutboxEventRepository.java` -- `lockDueBatch` SQL is the claim template (order, `SKIP LOCKED`, index `idx_outbox_events_pending`).
- `constant/outbox/RelayConstants.java` -- existing texts are asserted by tests; do not edit them.
- Tests `src/test/.../service/outbox/impl/`: `OutboxRelayIT` (`rowLockedByAnotherInstanceIsSkipped` = shape for relock tests; `twoInstancesNeverProcessTheSameRow` must stay green), `OutboxRelayRedisDownIT` (no edit); `support/OutboxRows` (`makeDue`, `state`), `support/LogCapture`.
- `ArchitectureTest` -- ports are interfaces; JDBC/Redis only in impl packages.
- `planing/Spec04.md` §4.2, §4.3, §4.5, §11.

## Tasks & Acceptance

**Execution:**
- [x] `constant/outbox/RelayConstants.java` -- add `CLAIM_EXHAUSTED_ERROR`, `CLAIM_EXHAUSTED_LOG`, `RELOCK_SKIPPED_LOG` (texts in Design Notes).
- [x] `repository/outbox/OutboxEventRepository.java` + impl -- replace `lockDueBatch` with `lockNextDue()` → `Optional<OutboxEvent>`; add `countTry(id, delay)` → new attempts (`RETURNING`) and `relock(id, claimedAttempts)` → `boolean`; `scheduleRetry(id, delay, error)` and `markFailed(id, error)` stop writing `attempts`; Javadoc.
- [x] `service/outbox/impl/OutboxRelayServiceImpl.java` + `service/outbox/OutboxRelayService.java` Javadoc -- `tick()` per Design Notes, with package-private `claimNext()` (TX A) and `work(OutboxEvent)` (TX B).
- [x] `OutboxRelayIT.java` -- adjust only if the API change needs it.
- [x] new `OutboxRelayCrashIT.java` -- `@Primary` store stub that throws `Error` for one dealership; `makeDue` between ticks, no sleeps -- AC1.1–AC1.3.
- [x] new `OutboxRelayRelockIT.java` -- rows locked or changed by hand in another transaction; one slow-stub case with a small `retry-delay` -- AC2.1–AC2.3.
- [x] `planing/Spec04.md` -- §4.2 TX A/TX B, §4.3 attempts counted at claim, §4.5 crash table (drop "accepted risk"), §11 risk line, rollout note (story Task 5).

**Acceptance Criteria:** story AC-S1.1–S1.4, AC-S2.1–S2.3, AC-F.1–F.2, AC1.1–AC1.3, AC2.1–AC2.3 pass; `ArchitectureTest` green.

## Design Notes

```
tick(): n = 0
  repeat batch-size times:
    TX A: row = lockNextDue()                   none → break
          n++
          row.attempts ≥ maxRetries+1 → markFailed(id, CLAIM_EXHAUSTED_ERROR) + log; continue
          claimed = countTry(id, retryDelay)    COMMIT: try counted
    TX B: relock(id, claimed) false → WARN, rollback, continue
          work; Redis/Runtime failure → retry-or-fail, COMMIT, break
  return n
```

- Story `markExhausted` = `markFailed(id, CLAIM_EXHAUSTED_ERROR)` (same SQL). The exhausted rule stays in the service, so the claim is `lockNextDue` + `countTry`.
- A JDBC failure inside TX B propagates; the try is already counted.
- `CLAIM_EXHAUSTED_ERROR = "CLAIM_EXHAUSTED: tries started but never finished"`; `CLAIM_EXHAUSTED_LOG` = story Task 1 text with `{}`; `RELOCK_SKIPPED_LOG = "Outbox event {} ({}) skipped: another relay instance owns try {}"`.

## Verification

**Commands:**
- `./gradlew :scheduler-batch:test` -- all green, Docker running.
- `git diff --stat -- scheduler-batch/src/test/java/com/keyloop/scheduler/batch/service/outbox/impl/OutboxRelayRedisDownIT.java` -- empty.

## Implementation Notes

- `claimNext()` returns `Optional<Claim>` (`Claim(event, exhausted)`, package-private record): the event carries the claimed `attempts`; `exhausted = true` means TX A already marked it FAILED, so `tick()` counts it and skips the work.
- `work(OutboxEvent)` returns `false` only on a Redis/Runtime error from the bump (tick stops). Only the bump is wrapped in `catch (RuntimeException)`; a JDBC failure in `relock`/`markProcessed`/`scheduleRetry` propagates, and `Error` is never caught.
- `OutboxRelayIT` needed no change. `OutboxRelayRelockIT` also covers the matrix "terminal state changed" row (processed / failed by hand) next to AC2.2.
- AC2.3 waits on the PostgreSQL clock until the claim's lease is over before instance B ticks (one bounded `Thread.sleep` of about `retry-delay`); the stub waits on a latch for at most `retry-delay` + 500 ms.
- Step-03 matrix audit (orchestrator): the "other RuntimeException" row had no test; added `OutboxRelayCrashIT.otherRuntimeExceptionIsRetriedLikeARedisError`. `./gradlew :scheduler-batch:test --rerun-tasks` → 55 tests green.
- Review pass 1 patches applied (B3, B4, B6/E4, B7, V1, E2); `./gradlew :scheduler-batch:test --rerun-tasks` → 56 tests green, `OutboxRelayRedisDownIT` unchanged.

## Spec Change Log

## Review Triage Log

Pass 1 (blind-hunter B, edge-case-hunter E, verification-gap V).

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| B1 | `CLAIM_EXHAUSTED_ERROR` overwrites the earlier Redis `last_error` | low | Real only when tries 1–3 failed on Redis and try 4 crashed; rare, and the text is spec-defined | reject (low, rare, fix edits spec text) |
| B2/E1 | Relock skip still spends a try; repeated skips can reach CLAIM_EXHAUSTED | low | Needs repeated foreign locks after the lease; story OP2 "every try counts", Task 2 accepts skip | reject (low, rare, fix adds a branch) |
| B3 | `RELOCK_SKIPPED_LOG` not logged via `logWithEventId` (no `[eventId]`) | low | `work()` logs it with plain `log.warn`; every other per-event line uses `logWithEventId` | patch |
| B4 | Retry WARN text is a literal in touched `markRetryOrFailed` | low | CLAUDE.md §3: fix rule breaks in touched files | patch |
| B5 | Lease reuses `retry-delay` | false | Story Task 1 decides it: "retry-delay … also the lease length" | reject |
| B6/E4 | AC2.3 can pass after A already committed | medium | `SlowStore` waits at most `retry-delay`+500 ms; no check that A is still blocked when B ticks | patch |
| B7 | `SLOW_DEALER` version key not cleaned; expects `"1"` | low | Fixed dealer + date, no Redis cleanup in `setUp`; direct one-line fix | patch |
| B8a | No end-to-end Redis retry→FAILED test | false | `OutboxRelayRedisDownIT` asserts attempts 1–4 and `FAILED_AFTER_RETRIES_LOG` | reject |
| B8b | No mixed Redis-then-crash test | low | Covered by the same claim rule as AC1.1 | reject |
| B8c | No bad-payload `attempts = 1` test | false | `OutboxRelayIT.badPayloadIsFailedOnTheFirstTryWithoutRetry` asserts it | reject |
| B8d/e | Relock-skip count / TX A failure not tested | low | Simple paths, rare | reject |
| B9 | Throughput (2 tx per row) not measured | false | Story R5 accepts it, measurement is Task 7 / load test | reject |
| B10 | Rollout rule has no code safeguard | low | Story Task 5 asks for a rollout note only; it is in Spec04 §4.5 | reject |
| B11 | `claimNext()` may NPE on null from `execute` | false | The callback always returns a non-null `Optional` | reject |
| V1 | Retry delay from `scheduleRetry` indistinguishable from the claim lease | medium | Pre-verified gap: dropping `next_attempt_at` from `scheduleRetry` keeps all tests green | patch |
| V2 | `relay()` loops again when the Redis error hits the `batch-size`-th row | low | Pre-existing: baseline `tick()` also returned `rows.size()` after a Redis error | defer |
| E2 | `CLAIM_EXHAUSTED_LOG` written before TX A commits | low | Log is inside the callback; a failed commit leaves the row pending after an ERROR | patch |
| E3 | `batch-size: 0` spins `relay()` forever | medium | Pre-existing (baseline same loop); fix is Task 3 validation, already deferred | defer |
