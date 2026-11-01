# Story — Outbox relay: bump the availability version, then mark the event processed

Status: ready for dev (review 2026-11-01 applied, see `story-outbox-relay.review.md`; OP3 decided; architecture review §11)
Owner: John (PM) · Date: 2026-11-01
Sources: Spec04 §4 (relay), §5 (schema V4), DLD §9–§10, ROADMAP D1, `cache-read-path.md`, CLAUDE.md §1–§4

**What this story builds.** Only §5 (Tasks 1–7) is new code. §4 describes the two runtime steps that already work. They are the baseline that the new code must keep green.

---

## 1. User story

**As a** customer who looks at free slots,
**I want** the slot list to change soon after somebody books, moves or cancels an appointment,
**so that** I do not pick a time that is already taken, and I do not miss a time that just became free.

Why: `GET /availability` reads from a Redis cache. A booking writes to PostgreSQL only. Without the relay, the cache shows old slots until the TTL (30–60 s) ends. The relay closes that gap in about one `poll-delay` (500 ms).

The relay never changes a booking. If it fails, the only cost is a stale cache until the TTL ends. The database constraints still block double-booking.

---

## 2. Words used in this story

| Word | Meaning |
|---|---|
| tick | One run of the relay (every `poll-delay`, 500 ms). |
| due row | An `outbox_events` row with `processed_at` and `failed_at` null and `next_attempt_at <= now()`. |
| dealership day | One date at one dealership. It has one version key. |
| try | One claim of a row. Each claim adds 1 to `attempts`. |
| retry | A try after the first one. `max-retries: 3` means 4 tries in total. |
| lease | `next_attempt_at` set in the future at claim time. It keeps other instances away only in the short gap between TX A and TX B, and makes a crashed row due again after `retry-delay`. During the work the row lock does the job. |
| Step 1 / Step 2 | The two runtime steps (§4). "Task" is only used for new work (§5). |

---

## 3. The flow in one picture (end state, after this story)

```
server-api (booking transaction)                 scheduler-batch (OutboxRelay, every 500 ms)
  INSERT appointment                               │
  INSERT outbox_events  ── COMMIT ──►  outbox_events (PENDING)
                                                   │   repeat up to batch-size times:
                                    ┌─ TX A: claim one row ─────────────────────────────┐  ◄ Task 1
                                    │  SELECT … LIMIT 1 FOR UPDATE SKIP LOCKED           │
                                    │  attempts ≥ max-retries + 1 → FAILED (exhausted)   │
                                    │  else attempts + 1, next_attempt_at = now()+lease  │
                                    └─ COMMIT  (the try is counted, even if the JVM dies)┘
                                    ┌─ TX B: work on that row ──────────────────────────┐  ◄ Task 2
                                    │  re-lock: SELECT … WHERE id AND attempts = claimed │
                                    │           FOR UPDATE SKIP LOCKED   (0 rows → skip) │
                                    │  parse payload ── bad ──► FAILED                   │
                                    │  STEP 1  INCR avail:ver:{dealer}:{date} + EXPIRE   │  ◄ §4.1
                                    │  STEP 2  processed_at = now()                      │  ◄ §4.2
                                    │  Redis error → retry or FAILED (§4.3)              │
                                    └─ COMMIT ───────────────────────────────────────────┘
                                                   │
server-api read path                               ▼
  GET avail:ver:D1:2026-11-05 → 4   ──►  reads avail:D1:2026-11-05:v4:…  (miss → rebuilt from PostgreSQL)
```

Inside TX B, Step 1 always runs before Step 2. §6 explains why the order matters.
TX B takes the row lock again before any work and holds it until `COMMIT`, as the relay does today. The lease only covers the gap between TX A and TX B (well under 1 ms), so a slow Redis call cannot end it (Task 2).

---

## 4. Baseline — the two runtime steps (already built)

### Payload contract (input of both steps)

```json
{
  "dealershipId": "0194244f-d800-76ca-b4eb-252b0f21ddb6",   // required, UUID
  "dates":    ["2026-11-05"],                                // dealership-local dates
  "oldDates": ["2026-11-04"],                                // optional (AppointmentRescheduled)
  "newDates": ["2026-11-05"],                                // optional (AppointmentRescheduled)
  "...":      "other fields are ignored"
}
```

- Dates to bump = **union** of `dates`, `oldDates`, `newDates`, with no duplicates, in first-seen order.
- Dates are local to the dealership, never UTC (Spec03 §6.1).
- The relay never loads the appointment. An event for a deleted appointment is still processed.
- Samples: `docs/contracts/outbox/*.json` (one per event type).
- Producer rule (not this story): every event must list **every** affected date. For example, `BusinessHoursChanged` lists each day in the open booking window whose hours changed. An empty list means "nothing to invalidate", so a producer bug that sends `[]` would leave the cache stale with no error.

### 4.1 Step 1 — `INCR avail:ver:{dealershipId}:{date}`

**What it does.** For each date in the union, add 1 to the version of that dealership day, then reset its TTL.

```
key    = "avail:ver:" + dealershipId + ":" + date      (RelayConstants.VERSION_KEY_PREFIX, KEY_SEPARATOR)
INCR   avail:ver:0194…ddb6:2026-11-05      3 → 4       (missing key → 1)
EXPIRE avail:ver:0194…ddb6:2026-11-05  2d              (scheduler.outbox.relay.version-key-ttl)
```

After Task 6, each date is bumped by one Lua script (seed if missing, `INCR`, `PEXPIRE`), and all scripts of one row go in **one pipeline** (one round trip). One row costs about one Redis timeout at most.

**Why a version and not DEL.** The cache key of a slot list contains the version:
`avail:{dealer}:{date}:v{N}:{serviceType}:{vehicleType}`. There are many such keys per day (one per service type × vehicle type). One `INCR` makes all of them unreachable together. No `SCAN`, no `DEL` of many keys. Old `v3` keys expire by their own TTL.

```
before   avail:ver:D1:2026-11-05 = 3     readers use  avail:D1:2026-11-05:v3:S1:V1  (stale)
INCR     avail:ver:D1:2026-11-05 = 4     readers use  avail:D1:2026-11-05:v4:S1:V1  (miss → rebuild)
```

**Same key on both sides.** `server-api` `AvailabilityConstants.VERSION_KEY_PREFIX` and `scheduler-batch` `RelayConstants.VERSION_KEY_PREFIX` are both `"avail:ver:"`. If only one of them changes, the relay bumps a key nobody reads. The test `cap2BookedEventBumpsTheVersionTheReadPathUses` guards this.

**Why EXPIRE 2 days.** A day nobody reads again should not keep a key in Redis forever. 2 days is longer than any slot TTL, so a live day does not lose its version because of the TTL (DLD §10).

**OP1 — `INCR` and `EXPIRE` as separate commands.** Accepted on 2026-11-01, then closed by Task 6: the Lua script runs `INCR` and `PEXPIRE` as one step, so a key can no longer be left without a TTL.

**Examples**

| Event | Payload dates | Redis commands (one pipeline) |
|---|---|---|
| AppointmentBooked | `dates [11-05]` | `INCR …:11-05`, `EXPIRE …:11-05` |
| AppointmentRescheduled | `dates [11-05]`, `old [11-04]`, `new [11-05]` | `INCR`/`EXPIRE` for 11-05 and 11-04 (11-05 is bumped once) |
| Any event with an empty union (`dates []`, no `oldDates`/`newDates`) | none | none → go to Step 2 right away |

**Code**
- Port: `store/outbox/AvailabilityVersionStore.bump(dealershipId, dates)` (Task 1 changes it from one date to a list)
- Redis impl: `store/outbox/impl/RedisAvailabilityVersionStore` (Lua script per date, pipelined, Task 6)

**Acceptance criteria**
- AC-S1.1 Given a payload with dates `[d1, d2]`, when the relay runs, then `avail:ver:{dealer}:d1` and `…:d2` each go up by exactly 1, and each has a TTL `> 0` and `≤ version-key-ttl`.
- AC-S1.2 Given a date in both `dates` and `newDates`, when the relay runs, then that date is bumped once.
- AC-S1.3 Given an empty union, when the relay runs, then no Redis command is sent and the row goes to Step 2.
- AC-S1.4 Given a Redis error (`DataAccessException`), when Step 1 runs, then that row does not go to Step 2 (see §4.3).

### 4.2 Step 2 — mark the event processed

**What it does.** After **all** dates of the row are bumped, set `processed_at = now()`. The row is then never due again.

```sql
UPDATE outbox_events SET processed_at = now() WHERE id = :id;   -- row is locked by TX B (Task 2)
```

```
PENDING ──(all INCR ok)──► PROCESSED ──(7 days, cleanup job)──► deleted
```

**Rules**
- Time comes from PostgreSQL `now()`, never the JVM clock. There are several instances, and PostgreSQL gives them one clock.
- The `UPDATE` runs in TX B, after the re-lock (Task 2). No other instance can change the row until `COMMIT`, so the write needs no extra guard.
- The due scan filters `processed_at IS NULL AND failed_at IS NULL`, so a processed row is never due again.
- `ck_outbox_events_terminal` (V4) makes sure a row is never both processed and failed. The re-lock checks both columns are null, so we never hit it.

**Code**
- Port: `repository/outbox/OutboxEventRepository.markProcessed(id)` (no change)
- SQL: `repository/outbox/impl/JdbcOutboxEventRepository`

**Acceptance criteria**
- AC-S2.1 Given Step 1 succeeded for every date of a row, when TX B commits, then that row has `processed_at` set and `failed_at` null.
- AC-S2.2 Given a processed row, when later ticks run, then it is never claimed again.
- AC-S2.3 Given an empty date union, when the relay runs, then the row is processed with no Redis call (`emptyDateUnionIsProcessedWithoutRedis`).

### 4.3 When Step 1 fails

`attempts` below is the value **after** the claim (try number, 1 to 4).

| Case | Step 1 | Step 2 | Row after TX B |
|---|---|---|---|
| Payload OK, Redis OK | done | done | PROCESSED |
| Bad payload (not JSON, no `dealershipId`, bad date) | skipped | skipped | FAILED right away, `attempts` unchanged (1 on first try), `last_error` = reason |
| Redis error, try 1–3 (`attempts ≤ max-retries`) | failed | skipped | PENDING, `next_attempt_at = now() + retry-delay` |
| Redis error, try 4 (`attempts > max-retries`) | failed | skipped | FAILED, `last_error` = Redis error, ERROR log `FAILED_AFTER_RETRIES_LOG` (Spec04 §4.6) |
| Key holds a non-integer (`ERR value is not an integer`) | failed | skipped | FAILED right away. Retrying cannot fix it. ERROR log `INVALID_VERSION_KEY_LOG` |
| Any other `RuntimeException` in TX B | failed | skipped | Same as a Redis error (retry, then FAILED), logged at ERROR |

- After the first Redis error, the tick ends. The rest of the due rows wait for the next tick, so we do not wait for many Redis timeouts in a row.
- All writes in this table run in TX B under the row lock from the re-lock (Task 2), so they need no guard.

**Acceptance criteria**
- AC-F.1 Given a bad payload, when the relay runs, then the row is FAILED with `attempts = 1` and no retry (`badPayloadIsFailedOnTheFirstTryWithoutRetry`).
- AC-F.2 Given Redis is down, when the relay runs, then tries 1–3 are rescheduled `retry-delay` apart and try 4 is FAILED with `attempts = 4` and the §4.6 log on that same tick (`redisErrorsRetryThreeTimesTwoSecondsApartThenFail`).
- AC-F.3 Given a version key that holds `"abc"`, when the relay bumps it, then the row is FAILED on the first try.

---

## 5. New work

### Task 1 — Claim one row at a time, and count the try before the work (OP2)

**Problem today.** `attempts` is saved in the same transaction that does Step 1 and Step 2. If the JVM dies, that transaction rolls back, so the try is not counted. A payload that crashes the JVM is retried forever.

**Decision (Dai Nam, 2026-11-01).** Every try counts, crash or not. Same limit as Redis errors: **max 3 retries, 2 s apart**, all from config. No new property, no literal `3` or `2s` in code.

```yaml
scheduler.outbox.relay:
  max-retries: 3     # after the first try; then FAILED
  retry-delay: 2s    # fixed, no backoff; also the lease length
```

**How.** One row per claim. TX A counts the try and commits. TX B locks the row again and does the work (Task 2).

```
tick():
  repeat up to batch-size times:
    TX A  row = claimNext()                         -- SELECT … LIMIT 1 FOR UPDATE SKIP LOCKED
          none → stop
          row.attempts ≥ max-retries + 1  → markExhausted(row)   FAILED, CLAIM_EXHAUSTED_LOG
          else  attempts = attempts + 1, next_attempt_at = now() + retry-delay
          COMMIT                                    -- the try is counted here
    TX B  relock(row) — 0 rows → skip this row      -- Task 2
          work(row)                                 -- parse → Step 1 → Step 2, or §4.3
          COMMIT                                    -- row lock released here
          Redis error → stop the tick
  return number of rows claimed (worked + exhausted)
```

```sql
-- claimNext, step 1 (TX A)
SELECT id, event_type, payload::text AS payload, attempts
FROM outbox_events
WHERE processed_at IS NULL AND failed_at IS NULL AND next_attempt_at <= now()
ORDER BY created_at, id
LIMIT 1
FOR UPDATE SKIP LOCKED;

-- claimNext, step 2 (TX A), when attempts < max-retries + 1
UPDATE outbox_events
SET attempts = attempts + 1,
    next_attempt_at = now() + :retryDelayMs * interval '1 millisecond'
WHERE id = :id
RETURNING attempts;                                     -- the claimed try number
```

Crash timeline (JVM dies inside TX B every time):

```
try 1  claim  attempts 0→1   crash ✘
try 2  +2 s   claim 1→2      crash ✘
try 3  +4 s   claim 2→3      crash ✘
try 4  +6 s   claim 3→4      crash ✘
+8 s   claim sees attempts 4 ≥ 4  →  FAILED, last_error = CLAIM_EXHAUSTED, ERROR log
```

Only the row that crashes loses tries. Other rows are claimed in their own TX A / TX B.

**Exhausted log.** The claim does not parse the payload, so it cannot fill dealership and dates. New constant:

```
Outbox event {eventId} ({eventType}) was marked FAILED after {attempts} attempts that did not finish (the relay stopped during the work); availability may be stale until cache TTL expiry
```

It is logged through `logWithEventId`, so the event id is the correlation id.

**Lease sizing.** The lease is `retry-delay` (2 s), but it only has to cover the gap between `COMMIT` of TX A and the re-lock in TX B (well under 1 ms). The work itself runs under the row lock, so `retry-delay` and `spring.data.redis.timeout` do not depend on each other. After a crash the connection drops, PostgreSQL releases the row lock, and the row is due again when the lease ends (`retry-delay` later).

**Code to change**
- `OutboxEventRepository`: replace `lockDueBatch` with `claimNext(retryDelay)` (returns `Optional<OutboxEvent>` with the claimed `attempts`), add `markExhausted(id, error)` and `relock(id, claimedAttempts)` (Task 2).
- `OutboxRelayServiceImpl`: `tick()` loops claim → work as above. Split into package-private `claimNext()` and `work(OutboxEvent)` so tests can call each one. Use the claimed `attempts` as is, never `+ 1`.
- `AvailabilityVersionStore.bump(dealershipId, List<LocalDate>)`: one pipeline per row (script in Task 6).
- `RelayConstants`: add `CLAIM_EXHAUSTED_ERROR`, `CLAIM_EXHAUSTED_LOG`, `INVALID_VERSION_KEY_LOG`, `RELOCK_SKIPPED_LOG`.
- `OutboxRelay.relay()`: no change. The loop goes on while `tick() == batch-size`, and exhausted rows count toward that number.

**Acceptance criteria**
- AC1.1 Given a row whose work never commits (test: `work` throws an `Error`), when it has been claimed 4 times, then the next claim marks it FAILED with `CLAIM_EXHAUSTED_ERROR` and the exhausted log. There is no 5th try.
- AC1.2 Given a batch where one row crashes, when the relay runs, then only that row's `attempts` goes up. The other rows are processed.
- AC1.3 Given 3 exhausted rows and `batch-size` due rows, when `tick()` runs, then it returns `batch-size` and the relay goes on without waiting.

### Task 2 — Re-lock the row at the start of TX B (decided 2026-11-01, §11.4)

The first statement of TX B checks that the row is still ours and locks it until `COMMIT`:

```sql
SELECT id
FROM outbox_events
WHERE id = :id AND attempts = :claimedAttempts      -- still our try
  AND processed_at IS NULL AND failed_at IS NULL     -- nobody finished it
FOR UPDATE SKIP LOCKED;                              -- nobody else works on it now
```

- 1 row → do the work. All writes (`markProcessed`, `scheduleRetry`, `markFailed`) run under this lock and need no extra `WHERE`.
- 0 rows → another instance has the row. Log WARN `RELOCK_SKIPPED_LOG`, roll back TX B, go to the next row. Do not throw. This only happens if the gap between TX A and TX B was longer than `retry-delay`.
- `scheduleRetry(id, retryDelay, error)` no longer changes `attempts` (TX A already counted the try). It only sets `next_attempt_at` and `last_error`.
- Cost: one primary-key `SELECT` per row.

**Acceptance criteria**
- AC2.1 Given the row is locked by another connection (test: lock it by hand in a separate transaction, as `rowLockedByAnotherInstanceIsSkipped` does), when TX B starts, then the relock returns 0 rows, the row is not changed, and `RELOCK_SKIPPED_LOG` is logged.
- AC2.2 Given the row's `attempts` was changed after the claim (test: update it by hand), when TX B starts, then the relock returns 0 rows and the work is skipped.
- AC2.3 Given a Redis call that takes longer than `retry-delay` (test: store stub waits `retry-delay` + 500 ms), when instance B ticks during that wait, then B does not claim the row and the row ends PROCESSED once, with `attempts = 1`.

### Task 3 — Validate relay settings at startup

`OutboxRelayProperties` gets `@Validated`:

| Property | Rule |
|---|---|
| `batch-size` | `@Positive` |
| `max-retries` | `@PositiveOrZero` |
| `retry-delay` | `@DurationMin(millis = 1)` |
| `poll-delay`, `version-key-ttl` | `@DurationMin(millis = 1)` |

**Acceptance criteria**
- AC3.1 Given `retry-delay: 0s` or `max-retries: -1`, when the app starts, then startup fails with a clear message.
- AC3.2 Given `max-retries: 1` and `retry-delay: 500ms`, when Redis is down and when the JVM "crashes" (Error in `work`), then both paths follow these values: 2 tries, 500 ms apart, then FAILED.

### Task 4 — Tests

| Test | Covers |
|---|---|
| `OutboxRelayIT` (existing, adjust to `claimNext`) | AC-S1.x, AC-S2.x, AC-F.1 |
| `OutboxRelayRedisDownIT` (existing, must stay green unchanged) | AC-F.2 |
| new `OutboxRelayCrashIT` — store stub throws `Error`, `outbox.makeDue(id)` between ticks (same trick as RedisDownIT, no sleeps) | AC1.1, AC1.2, AC1.3 |
| new `OutboxRelayRelockIT` — rows locked or changed by hand in a separate transaction (same shape as `rowLockedByAnotherInstanceIsSkipped`); one slow-store case for AC2.3 | AC2.1, AC2.2, AC2.3 |
| new `OutboxRelayPropertiesTest` | AC3.1, AC3.2 |
| new case in `OutboxRelayIT` for a non-integer key | AC-F.3 |

### Task 5 — Docs and rollout

- Update Spec04: §4.2 (claim per row, TX A / TX B), §4.3 table (`attempts` is counted at claim), §4.5 crash table and the "accepted risk" line, §8 risks bullet.
- Rollout note: old instances count the try in `scheduleRetry` and new ones count it at claim. Do not run both at once. Deploy with `scheduler.outbox.relay.enabled=false` on old instances first, or scale the relay to one instance during the rollout.

### Task 6 — Seed a new version key with Redis time (OP3)

**Decision (Dai Nam, 2026-11-01).** Option (a), with one rule: **a day that was never bumped always has version 0.** server-api does not change: a missing key still reads as `0` (RAC:175, `cache-read-path.md:20`; RAC = server-api `RedisAvailabilityCache.java` on branch `refactor/spec05-pr2-pr5`). Only the relay seeds a key, on the first bump.

```lua
-- KEYS[1] = avail:ver:{dealer}:{date}   ARGV[1] = version-key-ttl in ms
if redis.call('EXISTS', KEYS[1]) == 0 then
  local t = redis.call('TIME')                       -- Redis clock, same for every relay instance
  redis.call('SET', KEYS[1], t[1] * 1000 + math.floor(t[2] / 1000))
end
local v = redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return v
```

```
never bumped        GET → nil → reads 0            readers use …:v0:…
first bump 10:00    seed 1762000000000 → INCR → …001
second bump         …001 → …002
key evicted         GET → nil → reads 0 again      (day looks "never bumped")
next bump 10:05     seed 1762000300000 → INCR → …301000-ish, always > …002
```

**Invariants**
- I1: version is `0` if and only if the key is missing (never bumped, expired after `version-key-ttl`, or evicted).
- I2: every bump returns a number larger than any number that day had before, as long as there are fewer than 1 bump per ms per dealership day and the Redis clock does not go back.

**Code**
- `resources/redis/bump-version.lua` + a `RedisScript<Long>` bean, the same pattern as the server-api lock release script (RAC:109, 264).
- One script call per date, sent in one pipeline per row. One key per call keeps it valid if Redis ever runs as a cluster.
- No server-api change. Javadoc RAC:62–73 and `cache-read-path.md` get one line: "the relay seeds a new key with Redis time, so a restarted counter never repeats an old number".

**Acceptance criteria**
- AC6.1 Given a missing key, when the relay bumps it, then the value is ≥ Redis `TIME` in ms at that moment and the key has a TTL `> 0` and `≤ version-key-ttl`.
- AC6.2 Given a key at `N`, when the relay bumps it, then it is `N + 1` and the TTL is reset.
- AC6.3 Given a key that was bumped, then deleted (simulated eviction), when the relay bumps it again, then the new value is larger than the value before the delete.
- AC6.4 server-api reads a 13-digit version and builds `…:v1762000000001:…` keys (`AvailabilityCacheIT`, no code change expected).

### Task 7 — Health log lines (operability phase 1)

The three log lines and their rules are in §11.5 phase 1. New settings:

```yaml
scheduler.outbox.relay:
  health-log-interval: 1m    # how often the summary and eviction check run
  backlog-warn-age: 30s      # oldest due row older than this → WARN
```

**Code**
- `schedule/OutboxRelayHealthLog` (`@Scheduled(fixedDelayString = health-log-interval)`) calls `OutboxRelayService.health()` only (CLAUDE.md §1).
- `OutboxEventRepository.backlog()` → `(due, oldestDueAge)` in one SQL. `AvailabilityVersionStore.evictedKeys()` → Redis `INFO stats`.
- Log formats in `RelayConstants`. Both properties get `@DurationMin(millis = 1)` (Task 3).

**Acceptance criteria**
- AC7.1 Given 0 failed rows and an oldest due row younger than `backlog-warn-age`, when the health log runs, then it logs one INFO line.
- AC7.2 Given an oldest due row older than `backlog-warn-age`, or 1 failed row in the interval, when the health log runs, then the line is WARN.
- AC7.3 Given Redis `evicted_keys` went up since the last run, when the health log runs, then it logs the eviction WARN with the difference. Given no change, it logs nothing about evictions.
- AC7.4 Given the app starts with the relay on, then the startup line shows `max-retries`, `retry-delay` and `batch-size` from config.
- AC7.5 Given Redis is down, when the health log runs, then it logs the backlog line and skips the eviction check without an error.

---

## 6. Why Step 1 comes before Step 2

Redis and PostgreSQL do not share a transaction. So we pick the order where a crash costs only an extra cache miss, never a stale cache.

```
Order used:  INCR (Redis)  →  markProcessed (DB)  →  COMMIT TX B

crash after INCR, before COMMIT
  → TX B rolls back, row is still PENDING (try already counted in TX A)
  → after retry-delay the row is claimed again and INCR runs again  (version 4 → 5)
  → cost: one extra cache miss. Harmless.            ✔ at-least-once

Wrong order: markProcessed → COMMIT → INCR
crash after COMMIT, before INCR
  → row is PROCESSED, Redis never bumped
  → cache stays stale until TTL, and nobody retries. ✘ lost invalidation
```

So the relay is **at-least-once**: a version may go up more than once for one event, but it never goes up zero times for a processed event. The version number itself means nothing; only the fact that it changed matters. That is why repeating it is safe.

The same holds for a row with several dates: if the pipeline fails halfway, the retry bumps the done dates again. One extra miss, no harm.

---

## 7. Files (Spec05 layout)

```
scheduler-batch/src/main/java/com/keyloop/scheduler/batch
├── schedule/OutboxRelay                         @Scheduled entry, loops while tick() == batch-size   (no change)
├── service/outbox/OutboxRelayService            tick()
│   └── impl/OutboxRelayServiceImpl              claimNext() → work(row): parse → Step 1 → Step 2   (changed)
├── store/outbox/AvailabilityVersionStore        Step 1 port, bump(dealer, dates)                    (changed)
│   └── impl/RedisAvailabilityVersionStore       pipelined Lua: seed + INCR + PEXPIRE                (changed)
├── config/outbox/OutboxRelayConfig              RedisScript<Long> bean for the bump script          (new)
├── resources/redis/bump-version.lua             the script (Task 6)                                 (new)
├── repository/outbox/OutboxEventRepository      claimNext (new), markExhausted (new), relock (new),
│                                                scheduleRetry (no longer changes attempts)
│   └── impl/JdbcOutboxEventRepository           SQL                                                 (changed)
├── constant/outbox/RelayConstants               + CLAIM_EXHAUSTED_*, INVALID_VERSION_KEY_LOG, RELOCK_SKIPPED_LOG
└── config/outbox/OutboxRelayProperties          + @Validated                                        (changed)
```

---

## 8. Out of scope

- Cleanup of PROCESSED / FAILED rows (Spring Batch `OutboxCleanupJobConfig`, separate story).
- Writing the outbox row and listing every affected date (producer side, `story-book-appointment.md` and later stories).
- Replaying FAILED events by hand (no ops endpoint yet).

---

## 9. Decisions

- **OP1 — `INCR` and `EXPIRE` are separate commands.** Accepted 2026-11-01, then closed by Task 6 (one script).
- **OP2 — crash loop.** Decided 2026-11-01: fix it. See Task 1.
- **OP3 — version key evicted by Redis. Decided 2026-11-01: option (a), relay side only, with the rule "never bumped → version 0". See Task 6 and §11.** Analysis kept below for the record. Redis runs with `maxmemory-policy allkeys-lru` (`docker-compose.yml:39`). It can evict a version key while its slot keys are still alive. `server-api` reads a missing version as `0`, and the next `INCR` gives `1`. If an old `v1` slot key is still alive, readers get stale slots again. This is rare, but it loses an invalidation silently.
  Code refs below are on branch `refactor/spec05-pr2-pr5`. "RAC" = `server-api/.../cache/availability/impl/RedisAvailabilityCache.java`.

  **When can it happen? All four must be true:**
  1. Redis is full (`maxmemory 256mb`, `docker-compose.yml:39`). Upper bound of slot keys alive at once: 100 dealerships × 14 days (`AvailabilityConstants.MAX_DAYS`) × 100 service types × 8 vehicle types ≈ 1.1 M keys. At about 1 KB each (assumption, not measured) that is about 1 GB, so a full load test can fill 256 MB.
  2. LRU picks the version key. That is unlikely: every request reads the version key first (RAC:168) and the slot key after (RAC:185). So the version key is the most recently used key of its day. Redis LRU is approximate (it samples keys), so "unlikely" is not "never".
  3. The restarted counter reaches a version whose slot key is still alive. Slot keys live at most 60 s (`ttl 30s` + `ttl-padding 30s`, server-api `application.yml`). So the day must have reached that version within the last minute. In practice: a day that got its first bookings in the last minute.
  4. A reader asks for that exact service type and vehicle type in that window.

  **Worst result:** a stale slot list for at most 60 s. That is the same cost the project already accepts when an event is FAILED (Spec04 §4.3). A booking is still safe: "PostgreSQL decides every booking, so a stale or missing entry costs speed, never correctness" (RAC:48).

  | Option | Pros (with evidence) | Cons (with evidence) |
  |---|---|---|
  | **(a) Seed with Redis time (Lua), on both sides** — relay: `if not EXISTS then SET k <redis TIME ms>`, `INCR`, `PEXPIRE`. server-api: on a missing key, `SET k <redis TIME ms> NX EX ttl` | Closes the gap: a new number is always larger than any old one, so it can never match a live slot key. Also closes OP1, because one script runs as a whole (RAC:91–97 explains this for the lock release). The team already uses Lua here (`RedisScript` release script, RAC:109, 264). Redis `TIME` means no JVM clock skew between instances. | Must change **both** apps. Relay-only is not enough: a day never bumped reads as `0` (RAC:175), caches `v0`, and after an eviction reads `0` again and finds that `v0` key still alive. The read path gets a write on a cache miss. It breaks the written contract "missing → 0" (`cache-read-path.md:20`, RAC:64, RAC:72–73), so the spec and Javadoc must change. Version numbers grow to 13 digits, which adds about 12 bytes to every slot key and lock key. More tests in both apps. |
  | **(b) server-api: missing version → do not cache** | Small change in one file, one app. No new Redis write. | Breaks the cache for most days. Only the relay creates a version key (`RedisAvailabilityVersionStore`); server-api only reads it (RAC:168). So a day with no booking event yet has no key, and every read of it goes to PostgreSQL. Most of the 14-day window is like that. A version key that reaches its 2-day TTL also turns caching off again. This removes the load relief the cache exists for (ROADMAP D1), and the load test must run again. Does not fix OP1. A fix for this con turns (b) into the server-api half of (a). |
  | **(c) Accept + watch `evicted_keys`** | No code. The four conditions above make it rare, and the worst result has the same 60 s limit as a risk already accepted (Spec04 §4.3). Bookings stay safe (RAC:48). Adding a Redis `INFO stats` → `evicted_keys` alert is cheap and tells us when condition 1 starts to happen. | It is silent: no log or metric shows a stale hit when it happens. The risk grows with load (condition 1 can happen in a load test). The OP1 key leak stays. If `evicted_keys` goes above 0 in production, (a) is needed anyway. |

  PM recommendation was (c) + alert. The user chose (a) with the version-0 rule. That rule removes the main con of (a) above: server-api does not change, and "missing → 0" stays true.

---

## 10. Definition of done

- AC-S1.x, AC-S2.x, AC-F.x, AC1.x, AC2.x, AC3.x, AC6.x and AC7.x pass in the tests listed in Task 4, plus a new `RedisAvailabilityVersionStoreIT` for AC6.1–AC6.3 (Testcontainers PostgreSQL + Redis 8, no sleeps).
- `ArchitectureTest` green: `OutboxRelay` calls only `OutboxRelayService`; the service calls only the two ports.
- Same `avail:ver:` prefix in `server-api` and `scheduler-batch`, guarded by a test.
- Spec04 §4.2, §4.3, §4.5, §8 updated (Task 5).
- Task 7 log lines in place (§11.5 phase 1). Phase 2 (Jaeger) is a separate story.

---

## 11. Architecture review (Winston, 2026-11-01)

Scope: the relay as designed in this story (claim per row, re-lock in TX B, at-least-once bump) plus Task 6 (seed with Redis time, never bumped → 0).

### 11.1 What is sound

- **Outbox + version key is the boring, right pattern.** The event is written in the booking transaction, so it is never lost. One `INCR` per dealership day replaces a `SCAN` + `DEL` of up to 800 keys per day (100 service types × 8 vehicle types).
- **At-least-once with an idempotent effect.** A repeated bump costs one cache miss (§6). No exactly-once machinery is needed.
- **Safety does not depend on the cache.** PostgreSQL constraints decide bookings (RAC:48). Every relay failure costs at most "stale for ≤ 60 s".
- **The version-0 rule keeps the read side unchanged.** I1 is easy to reason about, and server-api needs no deploy.

### 11.2 Remaining risks

| # | Risk | When | Result | Decision |
|---|---|---|---|---|
| R1 | Eviction right after the **first** bump. The day reads `0` again and may hit a `v0` slot key built before that bump. | Redis full + LRU picks the version key (the hottest key of its day, RAC:168) + first bump < 60 s ago | Stale for ≤ 60 s | Accept. Make the precondition visible: phase 1 WARN log on new evictions (Task 7). |
| R2 | Redis clock goes back (failover to a replica with clock skew, NTP step). The new seed may be ≤ an old number. | Only after the key is missing *and* the clock went back by more than the time since the first bump | Same as R1 | Accept for single-node Redis (today). Re-check when Redis becomes managed or replicated. No code. |
| R3 | Rolling deploy: an old relay still seeds a missing key with `1`. | Only during rollout | Old risk for a few minutes | Task 5 rollout note + a startup log line that shows which relay design runs (Task 7). |
| R4 | No relay health signal: backlog size, age of the oldest due row, FAILED count. | Always | Ops see problems only through ERROR logs | Phase 1: one summary log line per interval (Task 7). Phase 2: Jaeger spans (§11.5). No metrics: Prometheus/Grafana are deferred (ROADMAP:150, DLD §12). |
| R5 | Two transactions per event (claim + work). | High event rate | Lower throughput than batch claim | Accept. Phase 1: the summary line carries rows and time per interval, read in the load test. Phase 2: span durations per claim and work. |

### 11.3 Simplicity — the concrete issues

Written for the lease-only design (score 7/10). S1–S4 are now solved by §11.4 (adopted). S5–S11 remain and are the cost of the OP2 and OP3 decisions.

Each part fixes a finding, but each one also brings its own problem. Test refs are on branch `refactor/spec05-pr2-pr5`.

**Lease (`next_attempt_at` = lease end)**

| # | Issue | Example |
|---|---|---|
| S1 | One column, two meanings. `next_attempt_at` in the future means "waiting for a retry" or "a worker has it right now". The table cannot tell which. | An operator runs `SELECT … WHERE next_attempt_at > now()` during an incident and cannot see which rows are in progress and which are waiting. |
| S2 | Hidden coupling between two settings in two namespaces. The lease is safe only while `spring.data.redis.timeout` (1 s) + SQL time < `scheduler.outbox.relay.retry-delay` (2 s). Nothing checks this. | Someone sets `retry-delay: 500ms` for faster retries. A slow Redis call (up to 1 s) now outlives the lease. A second instance claims the row: double bump and a try counted twice, with no error. |
| S3 | Correctness now depends on time. A long GC pause or a slow `UPDATE` ends the lease mid-work. That is why Task 2 needs the guard on every write, the `lease_lost` path, and a WARN. | Three write methods each get the same 3-part `WHERE`, and every new write method must remember it. |
| S4 | The hardest test in the story. `OutboxRelayLeaseIT` needs two instances, a latch inside a store stub, and a forced `makeDue`. | Latch-based concurrency tests are the first ones to become flaky in CI. |

**Two transactions per row (claim + work)**

| # | Issue | Example |
|---|---|---|
| S5 | A new middle state that is not in the Spec04 state machine: "claimed, try counted, not finished". | The health log `due` count leaves out rows under lease, so the backlog looks smaller during a slow period. |
| S6 | `attempts` changes meaning: from "tries that failed" to "tries started". | Spec04 §4.3 table, both ITs, the ERROR log text and anyone who reads `attempts` must learn the new rule. Old and new relays cannot run together (R3). |
| S7 | Two ways to reach FAILED, with two log formats and two `last_error` texts: exhausted at claim, or failed in the work. | An operator must know both messages to find all failed events. |
| S8 | More commits. Today: 1 commit per batch of up to 100 rows. After Task 1: 2 commits per row, so 200 for the same 100 rows. Each commit waits for a WAL flush. | A burst of 1 000 events (for example a `BusinessHoursChanged` storm) costs 2 000 commits instead of 10. |

**Lua script (Task 6)**

| # | Issue | Example |
|---|---|---|
| S9 | Logic in a second language, loaded from a resource file. Java unit tests cannot cover it; only an IT with real Redis can. | A typo in the script shows up only in `RedisAvailabilityVersionStoreIT`, not at compile time. |
| S10 | Version numbers become 13 digits and tests that expect small numbers break. | `OutboxRelayIT:97` expects `"1"` and `OutboxRelayIT:207` expects `1`. Both fail after Task 6. `AvailabilityCacheIT:140,190` still bump with a plain `increment`, so that test no longer copies what the relay does. |
| S11 | It depends on `TIME` inside a script (Redis ≥ 7). That is fine on `redis:8-alpine` today, but some Redis-compatible services and proxies block it. | A future move to a managed Redis needs a check first. |

**How they interact.** The parts are not independent: the lease length (S2) depends on how long the Lua call takes, and the meaning of `attempts` (S6) depends on the two transactions. A change to one forces a check of the others. That is the real cost behind "7/10".

### 11.4 Re-lock in the work transaction (removes S1–S4) — adopted 2026-11-01, now Task 2

Keep counting the try in TX A (the OP2 decision does not change). Then take the row lock again at the start of TX B and **hold it for the whole work**:

```
TX A   claim: lock → attempts + 1, next_attempt_at = now() + retry-delay → COMMIT   (same as Task 1)
TX B   SELECT … WHERE id = :id AND attempts = :claimed
                AND processed_at IS NULL AND failed_at IS NULL
       FOR UPDATE SKIP LOCKED                        -- 0 rows → someone else has it, skip
       parse → Step 1 → Step 2 → COMMIT               -- row lock held, as today
```

- The lease only has to cover the gap between `COMMIT` of TX A and the `SELECT` of TX B, which is well under 1 ms. A slow Redis call can no longer end it. This removes S2 and S3.
- Writes inside TX B need no guard because the row lock protects them. One ownership check at the start replaces three guarded writes, and `lease_lost` becomes "skip". This removes S3 and simplifies Task 2.
- `OutboxRelayLeaseIT` becomes "B sees the row locked and skips it", the same shape as the existing `rowLockedByAnotherInstanceIsSkipped`. This removes S4.
- S1 gets smaller: a row being worked is locked, and `pg_locks` shows it.
- S5–S11 stay. They come from the OP2 and OP3 decisions, not from the lease.

Cost: one more cheap `SELECT` per row. Effect: Simplicity 7 → 8, Testability 8 → 9 (see §11.6). **Decided by Dai Nam, 2026-11-01.**

### 11.5 Operability plan — two phases

Today the project has **logs** (log4j2, `[correlationId]` in every line, `common-logging`) and plans **Jaeger** (OTLP straight from the apps, DLD §12). Metrics are deferred (ROADMAP:150). So health signals go to logs first, then to traces.

**Phase 1 — logs (this story, Task 7).** Every value below comes from `application.yml` through `OutboxRelayProperties` (CLAUDE.md §3).

| Log line | Level | When | Covers |
|---|---|---|---|
| `Outbox relay started: claim per row, max-retries {}, retry-delay {}, batch-size {}, version seed by Redis time` | INFO | once at startup | R3 |
| `Outbox relay health: due {}, oldest due {}s, processed {}, retried {}, failed {} in the last {}` | INFO, or WARN when `oldest due > backlog-warn-age` or `failed > 0` | every `health-log-interval` | R4, R5 |
| `Redis evicted {} keys in the last {}; availability version keys may restart (R1)` | WARN | when Redis `INFO stats` `evicted_keys` went up since the last check | R1 |

- `due` and `oldest due` come from one SQL on the partial index `idx_outbox_events_pending`. `processed` / `retried` / `failed` are counted in memory by this instance.
- With several instances each one logs the same `due` numbers. That is accepted: no lock just for a log line.

**Phase 2 — Jaeger (separate story: `story-outbox-tracing.md`).**

```
outbox.relay.tick                                   (one span per tick)
 ├─ outbox.claim      event.id, event.type, attempts
 └─ outbox.work       event.id, dealership.id, dates.count, outcome = processed | retry | failed
     ├─ redis EVALSHA bump-version  ×N              (auto instrumentation)
     └─ jdbc UPDATE outbox_events                   (auto instrumentation)
```

- The span carries the event id, so a trace and the existing log lines can be joined.
- Biggest gain: link the relay span to the booking trace. server-api writes the W3C `traceparent` into the outbox row, and the relay starts `outbox.work` with a span link to it. Then Jaeger shows "booking → cache invalidated" as one picture, including the delay between them. This needs a V5 column (`trace_parent TEXT`) owned by server-api, so it belongs to the phase 2 story.
- Phase 2 can replace the R4/R5 health line for latency, but the backlog line (`due`, `oldest due`) stays: a trace cannot show rows that nobody has picked up yet.

### 11.6 Score

| Area | Score | Why |
|---|---|---|
| Correctness | 8/10 | At-least-once, row lock held during the work, crash counted (Tasks 1–2). R1/R2 leave a bounded ≤ 60 s gap. |
| Resilience | 8/10 | Fail-open reads, `SKIP LOCKED` across instances, retry limit for every failure kind. |
| Simplicity | 8/10 (was 7) | The re-lock (§11.4, Task 2) removes S1–S4, so no setting depends on another and no write needs a guard. S5–S11 (§11.3) come from the OP2/OP3 decisions and stay. |
| Fit with the codebase | 9/10 | PostgreSQL outbox, Redis `INCR`, Lua already used (RAC:109). No new technology. |
| Testability | 9/10 (was 8) | Testcontainers, test seams named, no sleeps. The latch-based lease test is gone: `OutboxRelayRelockIT` locks rows by hand, the same shape as an existing test. |
| Operability | 6/10 → 7 → 8 | Today: good ERROR logs with correlation id only. After phase 1 (Task 7): backlog, evictions and design version visible in logs → 7. After phase 2 (Jaeger + trace link to the booking): → 8. Stale reads stay silent. |
| Performance | 7/10 | One Redis round trip per row. Two transactions per event, not yet measured (R5). |
| **Overall** | **7.9/10 → 8.0 after phase 1 → 8.1 after phase 2** (was 7.6) | Ready to build. The next gains come from operability (§11.5), not from more correctness work. |
