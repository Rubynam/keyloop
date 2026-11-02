# Spec04 — Scaffolding Completion: Answers to Spec01 Open Questions

> Keyloop Technical Assessment · Scenario A: The Unified Service Scheduler
> Roadmap: **Phase 2 — Scaffolding (closing)** · Answers [Spec01 §9](Spec01.md#9-open-questions) · Builds on [Spec01](Spec01.md), [Spec03](Spec03.md), ROADMAP **D1**
> Status: ✅ Implemented · Owner: Dai Nam

---

## 1. Goal

Close Phase 2 with **as little technical debt as possible**. When Spec04 is done:

1. Every open question in [Spec01 §9](Spec01.md#9-open-questions) has a recorded answer (§2).
2. The skeleton has the **right shape** for the outbox work: no placeholder that points to the wrong tool, no schema change left for later that the producer (`server-api`) and the consumer (`scheduler-batch`) must agree on.
3. The behaviour of the outbox relay, the outbox cleanup and the booking retry is **decided and written down** (§4–§6), so the Phase 3 specs only implement it.

No business logic is written here: the relay loop, the cleanup steps and `BookingService` stay empty.

---

## 2. Answers to Spec01 §9

| # | Spec01 question | Answer | Why |
|---|---|---|---|
| Q1 | Outbox relay: Spring Batch job or plain scheduled worker? | **Plain worker** (`@Scheduled(fixedDelay)`) inside `scheduler-batch`. **Cleanup stays a Spring Batch job.** | The relay polls often and usually finds nothing. As a Batch job, every poll would write 5–7 metadata rows and need a fake unique parameter to make a new `JobInstance`. A hard kill would also leave the execution `STARTED`, which blocks the next launches until someone fixes it by hand. Restartability adds nothing, because the relay's progress already lives in `outbox_events` (`processed_at`, `attempts`). Cleanup runs once a day over many rows, which is the case Batch is built for: chunked deletes, run history and restart. |
| Q2 | Need a `scheduler-common` module for shared event payloads? | **No.** The payload is a **JSON contract** (§4.4), and both sides test against the same sample files. | A shared jar ties the release of the API and the worker together, and the worker only reads 3–4 fields. Reconsider if a second consumer appears or the events become typed classes used by both apps. |
| Q3 | Batch metadata in the same database (separate schema) or a dedicated one? | **Same database, schema `batch`**, plus a **retention rule** (CLN-04). | Once the relay is not a Batch job, the metadata is small. The heartbeat alone still writes about 2,880 executions a day, though, so without pruning the metadata grows forever. That is the debt to remove now, not the location. |
| Q4 | Testcontainers: introduce now or in Phase 4? | **Now**, in this spec (TST-01…03). | The relay relies on `FOR UPDATE SKIP LOCKED`, Postgres time and real Redis commands, which mocks cannot prove. Adding the base class later means rewriting every integration test written in between. |

---

## 3. Scope

### In scope
- Migration **V4**: retry and failure columns on `outbox_events` (§5).
- Seed generator and seed SQL updated to the V4 columns.
- Replace the placeholder `OutboxRelayJobConfig` with an empty worker placeholder `outbox/OutboxRelay` (REL-01). `OutboxCleanupJobConfig` stays a Batch placeholder.
- Configuration keys for the relay and the cleanup, with the decided defaults (§7). Both stay `enabled: false`.
- Testcontainers base for both apps (TST-01…03).
- Payload sample files for the contract tests (§4.4).
- Spec01 §9 links to this spec. DLD diagrams 02 (ERD, constraints), 05, 07, 09 and 11 are updated **after approval** (same rule as Spec01).

### Out of scope (Phase 3 specs)
- The relay loop, cleanup steps and `BookingService` code.
- `LISTEN/NOTIFY` wake-up (§9).
- Metrics and alert rules (ROADMAP: deferred).

---

## 4. Outbox relay — decided behaviour

### 4.1 Runtime
- One Spring bean `OutboxRelay` in `scheduler-batch`, method `@Scheduled(fixedDelayString = "${scheduler.outbox.relay.poll-delay}")`.
- **No ShedLock.** Several instances run together safely: `SKIP LOCKED` gives each one different rows. A lock would only make the instances queue up.
- One tick claims up to `batch-size` rows, one row at a time, two short transactions per row (§4.2). If the tick claimed `batch-size` rows, the next tick starts at once instead of waiting for the next poll.

### 4.2 One tick

Repeat up to `batch-size` times. Each row gets two transactions (story-outbox-relay Tasks 1–2):

```sql
-- TX A: claim one row and count the try
SELECT id, event_type, payload, attempts
FROM outbox_events
WHERE processed_at IS NULL AND failed_at IS NULL
  AND next_attempt_at <= now()
ORDER BY created_at, id            -- Spec03 §6.8: ids alone are not a strict time order
LIMIT 1
FOR UPDATE SKIP LOCKED;            -- none → end the tick

-- attempts >= max-retries + 1 → failed_at = now(), last_error = CLAIM_EXHAUSTED (§4.5), no work
UPDATE outbox_events
SET attempts = attempts + 1, next_attempt_at = now() + :retryDelay   -- lease
WHERE id = :id
RETURNING attempts;                -- the claimed try number
COMMIT;                            -- the try is counted, even if the JVM dies now

-- TX B: lock the row again, then work under that lock
SELECT id FROM outbox_events
WHERE id = :id AND attempts = :claimed
  AND processed_at IS NULL AND failed_at IS NULL
FOR UPDATE SKIP LOCKED;            -- 0 rows → WARN, roll back, next row
```

Then, in TX B:
1. Read the dates from the payload (§4.4).
2. For each `(dealershipId, date)`: `INCR avail:ver:{dealershipId}:{date}` and `EXPIRE … 2 days` (DLD §10).
3. On success: `processed_at = now()`.
4. On failure, apply §4.3. After a Redis error the tick ends; the other due rows wait for the next tick.

Then `COMMIT`. The writes of TX B need no extra `WHERE`: the re-lock protects them. The lease (`next_attempt_at` set at claim) only has to cover the short gap between TX A and TX B. All timestamps come from Postgres `now()`, never from the JVM clock (same rule as ShedLock `usingDbTime()`).

The tick returns the number of rows claimed, exhausted rows included.

### 4.3 Retry policy

**One try plus at most 3 retries, 2 seconds apart (fixed delay, no backoff). After that the event is `FAILED`.**

`attempts` is counted at the claim (TX A), before the work. So it means "tries started", and the work never changes it.

| Try | Runs at (about) | `attempts` after claim | On failure |
|---|---|---|---|
| First try | first tick after commit | 1 | `next_attempt_at = now() + 2s` |
| Retry 1 | +2 s | 2 | `next_attempt_at = now() + 2s` |
| Retry 2 | +4 s | 3 | `next_attempt_at = now() + 2s` |
| Retry 3 | +6 s | 4 | **`failed_at = now()`**, `last_error` set, log ERROR |

- "About" means: plus up to one `poll-delay`, because a retry waits for the next tick. The relay never sleeps while holding row locks.
- **Error kinds:**
  - *Retryable*: Redis unreachable or timing out, or any other runtime error during the bump. These follow the table above.
  - *Non-retryable*: payload is not a JSON object, `dealershipId` is missing, or a date is invalid. These go straight to `FAILED` on the first try (`attempts = 1`), because retrying cannot fix them.
  - *Crash* (the JVM dies, or an `Error`, during TX B): TX B rolls back but the try stays counted. Crashes and Redis errors share the same limit (§4.5).
- Both values come from `scheduler.outbox.relay.max-retries` and `retry-delay` (§7).
- **The booking is never changed by the relay.** An event in `FAILED` means only that the cache may be stale until its TTL (30–60 s) ends. The database constraints still block double-booking, and the next event for the same day bumps the version anyway (ROADMAP D1).

### 4.4 Payload contract (answers Q2)

```json
{
  "dealershipId": "uuid",                 // required
  "dates":    ["2026-11-05"],             // dealership-local dates, may be empty
  "oldDates": ["2026-11-04"],             // optional, AppointmentRescheduled
  "newDates": ["2026-11-05"],             // optional, AppointmentRescheduled
  "...": "other fields are ignored by the relay"
}
```

- The relay bumps the union of `dates`, `oldDates` and `newDates`. An empty union is a no-op and the event is marked processed (Spec03 §6.8 "empty dates").
- Dates are **local to the dealership** (Spec03 §6.1), never UTC.
- The relay never loads the aggregate, so an event for a deleted appointment still works (Spec03 §6.8).
- Sample files live in `docs/contracts/outbox/*.json`, one per event type. The `server-api` producer test checks that it writes payloads matching them, and the `scheduler-batch` relay test reads them.

### 4.5 Crash safety

| Crash point | What happens | Result |
|---|---|---|
| In TX A, before `COMMIT` | Connection drops → Postgres rolls back and releases the row lock | The try is not counted; the row is due at once for another instance or after restart |
| After TX A `COMMIT`, before or during TX B | TX B rolls back, the try stays counted, the lease (`retry-delay`) keeps the row away | The row is due again after `retry-delay` and is claimed as the next try |
| After `INCR`, before TX B `COMMIT` | Rollback, but Redis is already bumped | The bump runs again on the next try: one extra cache miss, no harm (`INCR` is idempotent in effect) |
| Every try crashes | `attempts` reaches `max-retries + 1` | The next claim marks the row FAILED with `last_error = CLAIM_EXHAUSTED: tries started but never finished` and logs ERROR (§4.6). No more tries |
| Whole worker down | Events stay in the table (written in the booking transaction) | Drained after restart. Meanwhile the cache is stale for at most its TTL |
| Process hangs inside a transaction | Row lock is held | Ended by `idle_in_transaction_session_timeout` (REL-06) and the Redis command timeout (REL-05) |

An event that makes the JVM crash is no longer retried forever: each crash costs one try, with the same limit as Redis errors (story-outbox-relay Task 1, OP2).

**Rollout.** Old relays count the try in the work and new ones count it at the claim. Do not run both at once: set `scheduler.outbox.relay.enabled=false` on the old instances first, or scale the relay to one instance during the rollout.

### 4.6 Failure message (English, for operators)

Logged at ERROR with the event id as the correlation id:

```
Cache invalidation failed after 4 attempts for outbox event {eventId} ({eventType}, dealership {dealershipId}, dates {dates}); availability may be stale until cache TTL expiry. Last error: {lastError}
```

For a non-retryable error:

```
Outbox event {eventId} ({eventType}) has an invalid payload and was marked FAILED without retry: {reason}
```

When every try started but none finished (§4.5):

```
Outbox event {eventId} ({eventType}) was marked FAILED after {attempts} attempts that did not finish (the relay stopped during the work); availability may be stale until cache TTL expiry
```

When TX B cannot re-lock its claimed row (WARN, not a failure):

```
Outbox event {eventId} ({eventType}) skipped: another relay instance owns try {attempts}
```

End users never see these messages. See §6 for what users see.

---

## 5. Schema — migration V4 (owned by `server-api`)

```sql
-- V4__outbox_retry.sql
ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN failed_at       TIMESTAMPTZ,
    ADD COLUMN last_error      TEXT,
    -- an event ends either processed or failed, never both
    ADD CONSTRAINT ck_outbox_events_terminal CHECK (processed_at IS NULL OR failed_at IS NULL),
    ADD CONSTRAINT ck_outbox_events_failed   CHECK (failed_at IS NULL OR failed_at >= created_at);

-- The relay scans only due, unfinished events
DROP INDEX idx_outbox_events_pending;
CREATE INDEX idx_outbox_events_pending
    ON outbox_events (next_attempt_at)
    WHERE processed_at IS NULL AND failed_at IS NULL;
```

States (replaces DLD §9 outbox state machine after approval):

```
PENDING ──ok──────────────────────────▶ PROCESSED ──(7 days)──▶ deleted
PENDING ──retryable error, attempts ≤ 3──▶ PENDING (next_attempt_at = now() + 2s)
PENDING ──4th failure / bad payload────▶ FAILED ──(30 days)──▶ deleted
```

Seed changes (Spec03 §6.8): the "25 attempts" row becomes **FAILED** with a `last_error`, and one PENDING row gets a `next_attempt_at` in the future (not due yet).

---

## 6. Booking retry — decided behaviour (input for the booking spec)

The "booking failed" message belongs to **`BookingService`**, where the client is still waiting for the response. It does not belong to the relay, which runs after `201 Created` has already been sent.

- `BookingService` tries **at most 3** `(bay, technician)` pairs (DLD §5 loop, `N = 3`).
- **No wait between tries:** each try uses a *different* pair, so waiting would not free the one that conflicted and would only add latency. The 2 s delay applies to the relay only.
- If all tries fail, the transaction is rolled back and the client gets:

```
409 Conflict  (RFC 7807 ProblemDetail)
title:  "Booking failed"
detail: "Booking failed: no technician or service bay is available for the requested time. Please choose another slot."
```

---

## 7. Configuration (`scheduler-batch/application.yml`)

| Key | Default | Meaning |
|---|---|---|
| `scheduler.outbox.relay.enabled` | `false` | Turned on by the relay implementation spec |
| `scheduler.outbox.relay.poll-delay` | `500ms` | Delay between ticks |
| `scheduler.outbox.relay.batch-size` | `100` | Rows per transaction |
| `scheduler.outbox.relay.max-retries` | `3` | Retries after the first try |
| `scheduler.outbox.relay.retry-delay` | `2s` | Fixed delay between tries |
| `scheduler.outbox.relay.version-key-ttl` | `2d` | `EXPIRE` on `avail:ver:*` |
| `scheduler.outbox.cleanup.enabled` | `false` | Turned on by the cleanup implementation spec |
| `scheduler.outbox.cleanup.cron` | `0 0 3 * * *` | Daily, 03:00 |
| `scheduler.outbox.cleanup.processed-retention` | `7d` | Delete PROCESSED older than this |
| `scheduler.outbox.cleanup.failed-retention` | `30d` | Delete FAILED older than this (kept longer for investigation) |
| `scheduler.outbox.cleanup.batch-metadata-retention` | `7d` | Prune Spring Batch metadata older than this |
| `spring.data.redis.timeout` | `1s` | Upper bound for one Redis command |

The keys move from `scheduler.jobs.outbox-*` to `scheduler.outbox.*`, because the relay is no longer a job. `scheduler.jobs.*` stays for Batch jobs (heartbeat).

---

## 8. Requirements

### 8.1 Relay skeleton

| ID | Requirement |
|---|---|
| REL-01 | Delete `job/OutboxRelayJobConfig`. Add `outbox/OutboxRelay` (empty `@Scheduled` method, `@ConditionalOnProperty("scheduler.outbox.relay.enabled")`) and `outbox/OutboxRelayProperties` (`@ConfigurationProperties`, the keys in §7). |
| REL-02 | The relay behaviour in §4 is the contract for the implementation spec. Nothing in §4 is changed there without updating this spec. |
| REL-03 | No ShedLock on the relay (§4.1). |
| REL-04 | Timestamps come from Postgres `now()` only. |
| REL-05 | `spring.data.redis.timeout = 1s`. |
| REL-06 | The relay connection sets `idle_in_transaction_session_timeout = 30s` (e.g. through the JDBC URL `options` parameter of `scheduler-batch`). |

### 8.2 Cleanup skeleton

| ID | Requirement |
|---|---|
| CLN-01 | `job/OutboxCleanupJobConfig` stays a Spring Batch placeholder, launched by `JobScheduler` with ShedLock (one run per day across instances). |
| CLN-02 | Planned steps: delete PROCESSED older than `processed-retention`, delete FAILED older than `failed-retention`. Deletes run in chunks (e.g. 1,000 ids per transaction), never as one big `DELETE`. |
| CLN-03 | Planned step: prune `batch.batch_*` rows of finished executions older than `batch-metadata-retention`, children first (step context → step execution → job context → params → job execution → job instance). |
| CLN-04 | Until CLN-03 is implemented, the heartbeat cron default goes from 30 s to **5 min**, which cuts its metadata by 10×. Spec01 acceptance criterion "every ~30 s" is updated to match. |

### 8.3 Schema

| ID | Requirement |
|---|---|
| SCH-01 | Add `V4__outbox_retry.sql` exactly as in §5. Do not edit V1–V3 (already applied on existing databases). |
| SCH-02 | `seeding/generate.py` writes the new columns; regenerate `13_outbox_events.sql`; `verify.sql` checks that no row is both processed and failed. |

### 8.4 Tests

| ID | Requirement |
|---|---|
| TST-01 | Add Testcontainers (`postgres:18-alpine`, `redis:8-alpine`, the same tags as compose) through the Spring Boot BOM. |
| TST-02 | One shared base per app (`@SpringBootTest` + `@ServiceConnection`). `server-api` runs Flyway in it; `scheduler-batch` uses a container where the API migrations were applied first. |
| TST-03 | Add context-load tests based on TST-02 (`SchemaMigrationTest` in `server-api`; `SchedulerBatchApplicationTest`, `OutboxRelayEnabledTest` in `scheduler-batch`). Unit and slice tests (e.g. `HeartbeatTaskletTest`, `@WebMvcTest`) stay without containers. |
| TST-04 | Add the payload samples in `docs/contracts/outbox/` (§4.4): `AppointmentBooked`, `AppointmentCancelled`, `AppointmentRescheduled`, `BayBlocked`, `TechnicianUnavailable`, `BusinessHoursChanged`. |

---

## 9. Implementation Steps

1. **Schema**: V4 migration, seed generator, regenerated seed, `verify.sql` check.
2. **Relay skeleton**: delete the old placeholder, add `OutboxRelay` + `OutboxRelayProperties`, move the config keys, set the Redis timeout and the idle-in-transaction timeout.
3. **Cleanup skeleton**: config keys, heartbeat cron to 5 min.
4. **Tests**: Testcontainers base in both apps, rewrite the context-load tests, add the payload samples.
5. **Docs** (after approval): tick Spec01 §9 (links are already there), DLD diagrams 02 (outbox columns, index, check), 05 (`N = 3`), 07 (retry and FAILED), 09 (FAILED state), 11 (409 message), ROADMAP D1 bullets, Spec03 §6.8.

---

## 10. Acceptance Criteria

- [x] `flyway_schema_history` shows `4 outbox retry`. `\d outbox_events` shows `next_attempt_at`, `failed_at`, `last_error` and both new checks. *(`SchemaMigrationTest`)*
- [x] `UPDATE outbox_events SET processed_at = now(), failed_at = now() …` fails with `ck_outbox_events_terminal`. *(`SchemaMigrationTest`)*
- [x] `seeding/seed.sh` passes, with at least one FAILED row and one PENDING row that is not due yet. *(`verify.sql` checks both; run twice on a fresh Postgres 18 with V1–V4)*
- [x] `OutboxRelayJobConfig` is gone from the code (only Spec01's history mentions it). `OutboxRelay` exists and does not load while `scheduler.outbox.relay.enabled=false`. *(`SchedulerBatchApplicationTest`, `OutboxRelayEnabledTest`)*
- [x] `./gradlew build` passes with Docker running; the integration tests start Postgres 18 and Redis 8 containers.
- [ ] `batch.batch_job_execution` grows by about 12 rows per hour (heartbeat every 5 min). *(needs the compose stack rebuilt, and a local `.env` that does not pin `HEARTBEAT_CRON` to 30 s)*
- [x] Every Spec01 §9 item is ticked and links to §2 of this spec.

---

## 11. Trade-offs & Open Questions

- **Polling only, no `LISTEN/NOTIFY` yet.** A 500 ms poll gives sub-second invalidation, which is enough while the cache TTL is 30–60 s. `NOTIFY` needs a dedicated long-lived connection and does not survive a restart, so polling would be needed anyway. Revisit if relay lag becomes a problem.
- **Fixed 2 s retry delay, no backoff.** Simple and matches the request. During a long Redis outage every event fails in about 6 s, which is acceptable because FAILED only means a cache that is stale until TTL.
- **FAILED events are not replayed automatically.** An operator can reset them (`UPDATE … SET failed_at = NULL, attempts = 0, next_attempt_at = now()`). A replay endpoint or command is left for later.
- **JVM-killing event** (§4.5). Closed: the try is counted in TX A, so such an event is FAILED after `max-retries` + 1 tries. Cost: two transactions per event instead of one per batch (more commits during a burst).
- [ ] Which alert (log-based or metric) fires on a FAILED event while Prometheus is deferred?
