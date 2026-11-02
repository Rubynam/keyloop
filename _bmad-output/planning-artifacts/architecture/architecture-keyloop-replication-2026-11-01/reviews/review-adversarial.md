---
review-of: ARCHITECTURE-SPINE.md (keyloop-replication, draft 2026-11-01)
lens: adversarial (two units each obey every AD, yet compose wrongly)
reviewed-against: server-api (RedisAvailabilityCache, AvailabilityServiceImpl, JdbcAvailabilityRepository, AppointmentServiceImpl, CandidateSelectorImpl, Jdbc*Repository), scheduler-batch (OutboxRelayServiceImpl, JdbcOutboxEventRepository, RedisAvailabilityVersionStore, JdbcRedisHeartbeatProbe, OutboxCleanupJobConfig), both application.yml, docker-compose*.yml, loadtest/simulator/simulate.py, V2/V4 migrations
date: 2026-11-01
verdict: NOT READY. 2 critical holes (one is a contradiction inside the spine itself), 3 high, 4 medium/low.
---

# Adversarial review: keyloop replication spine

## Verdict

The direction is sound: one role per port, the gate before `INCR`, two Redis factories. But the spine binds roles **per class** while the code shares classes across paths. It also treats `preferSecondary` as a node choice when it is really a per-connection choice, and it assumes compose services keep their roles after a promote. Each of these lets two builders obey every AD to the letter and still produce wrong bookings, a split brain, or an outage on replica loss.

Severity scale used below:
- **Critical**: wrong writes, data loss, or the spine contradicts itself.
- **High**: outage, or the AD-5 guarantee silently fails.
- **Medium**: load or herd risk, or a liveness edge.
- **Low**: naming or wiring friction.

Stale cache alone is always bounded by slot TTL ≤ 60 s (`ttl 30s + ttl-padding 30s`), so pure staleness is never rated above High.

## Holes

### H1: CRITICAL. AD-4 sends the booking path to the replica (self-contradiction)

**Unit A** (availability read path): follows AD-4 and wires `JdbcAvailabilityRepository` to `replicaJdbcClient`.
**Unit B** (booking path): follows AD-4 ("booking, candidate selection ... primary only") and changes nothing, because its own repositories (`JdbcAppointmentRepository`, `JdbcCandidateRepository`, `JdbcOutboxRepository`) already use the primary.

The code shows that the booking path reuses `AvailabilityRepository`:
- `AppointmentServiceImpl:118-120`: `availability.findDealership(...)`, `findServiceDurationMinutes(...)`
- `CandidateSelectorImpl:53-68`: `findFittingBayIds`, `findQualifiedTechnicianIds`, **`findBookings`** (the overlap check)
- The `JdbcCandidateRepository` Javadoc says it outright: "Fit and overlap reuse `AvailabilityRepository`".

So after A ships, candidate selection reads bookings from a replica that is behind:

```
t0  POST book 09:00 → pair (B1,T1) committed on primary
t1  POST book 09:00 again → selector reads replica (lag) → (B1,T1) looks free
    → tries B1/T1 → 23P01 → next pair ... max-pairs=3 used up on stale "free" pairs → 409
    although (B2,T3) was really free
t2  cancel 10:00 committed → rebook 10:00 → replica still shows CONFIRMED → 409 / 422
t3  new dealership/service type on primary, not yet replayed → booking returns 404
```

The exclusion constraint keeps data correct, but the API returns wrong 409/422/404 answers. This breaks AD-4's own "Prevents: booking decisions ... made on lagging data".

**Proposed rule (tighten AD-3 + AD-4):**
> **AD-4a: Role is bound per bean, not per class.** A repository interface used by more than one path gets one bean per role it needs. `AvailabilityRepository` has two beans: `replicaAvailabilityRepository` (`replicaJdbcClient`) is injected only by `AvailabilityServiceImpl`, and `primaryAvailabilityRepository` (`primaryJdbcClient`) is injected by `AppointmentServiceImpl` and `CandidateSelectorImpl`. Bean names live in the constants class. Every injection point of a dual-role interface uses `@Qualifier`; an unqualified injection of a dual-role interface fails ArchUnit (new ARC-09: "only `AvailabilityServiceImpl` and the relay gate service may depend on a replica-qualified bean").

### H2: CRITICAL. Manual failover plus static compose roles leads to split brain on the next `docker compose up`

**Unit A** (compose/runbook builder): follows AD-1 with services named `postgres-primary` (initdb'd primary) and `postgres-replica` (`pg_basebackup -R`). Follows AD-2: stop `postgres-primary`, run `pg_promote()` on `postgres-replica`, re-seed later.
**Unit B** (app config builder): follows AD-3. Both URLs list both hosts, and the write URL uses `targetServerType=primary`.

After a promote, anyone who runs the documented scale command restarts the stopped service. The command is `docker compose up -d --scale server-api=3 --no-recreate` (compose file comment), and `up` starts stopped services. `postgres-primary` still has its old data dir and no `standby.signal`, so it comes up **writable**. Now both hosts answer as primary. pgjdbc `targetServerType=primary` takes the **first writable host in the list**, which is the old primary. New bookings land on the stale node, and the promoted node's writes are orphaned. If the volume was wiped instead, the entrypoint `initdb`s an **empty** writable primary, which is just as bad. Redis has the same problem: `redis-master` restarts without `--replicaof`, Lettuce sees two `ROLE master` nodes, `INCR` and version `GET` can split across them, and invalidation is lost until TTL.

AD-2 says "the old primary never restarts as writable", but nothing in compose enforces it.

**Proposed rule (tighten AD-1 + AD-2):**
> **AD-2a: Roles are runtime state, not service names.** Postgres services are `pg-a` / `pg-b` and Redis services are `redis-a` / `redis-b`, each pair on one shared entrypoint. On start, the Postgres entrypoint asks the peer `SELECT pg_is_in_recovery()`. If the peer is a primary, this node **must** start as a standby (it runs `pg_basebackup -R` if its data dir lacks `standby.signal` or is from an old timeline) and never `initdb`. It starts as primary only when no peer is reachable **and** its own data dir is not marked `demoted`. The promote script writes the `demoted` marker into the old primary's volume before promoting. Redis works the same way: the entrypoint runs `--replicaof <peer>` when the peer reports `role:master`. Hosts that stay on a fixed role are forbidden. `restart:` policy is `no` for data services. Env names become `DB_HOSTS=pg-a,pg-b` and `REDIS_NODES=redis-a,redis-b`. Write order no longer matters, because at most one node can be writable.

### H3: HIGH. The gate proves visibility on whatever node *its* connection landed on, not on the node readers use

**Unit A** (relay gate): follows AD-5 and checks `SELECT 1 FROM outbox_events WHERE id=?` through `replicaJdbcClient`.
**Unit B** (availability rebuild): follows AD-3/AD-4 and reads through `replicaJdbcClient`.

`preferSecondary` is decided **per physical connection** when it is opened, and Hikari keeps a connection for up to `maxLifetime` (30 min default). If the replica blips (restart, re-seed after AD-2, network), the relay's replica pool opens connections on the **primary**. The gate then passes at once. Meanwhile server-api's replica pool is on the replica, which is catching up after its restart and can be seconds to minutes behind. The relay runs `INCR`, a reader rebuilds from the lagging replica, and stale slots are pinned under the new version until TTL. This is exactly what AD-5 was written to stop. The reverse case (gate on replica, readers on primary) is harmless. The spine's own claim, "a manual promote needs no app config change", makes this certain after every promote: the relay's replica pool sits on the new primary while the re-seeded node catches up.

Two smaller points:
- Version-read ordering (versions from the master *before* the PG read, `AvailabilityServiceImpl`) is safe **only** while every replica connection sees one monotonic node. H3 is the case that breaks it.
- Gating on "row present" is fine against cleanup. CLN-01 deletes only terminal rows, and the gate runs before `markProcessed`. This was checked and is not a hole. Pin it anyway (rule below), because any future cleanup of pending rows would turn the gate into a guaranteed timeout.

**Proposed rule (replace the AD-5 mechanism, keep the gate as an optimisation):**
> **AD-5a: Freshness is proved by LSN on the reader's own connection.** In TX A the relay reads `pg_current_wal_lsn()` on the primary after the claim, and stores it with the bump: `SET avail:minlsn:{D}:{date} <lsn>` right after `INCR` (same TTL as the version key, master template). A rebuild that will **write** the cache runs its bookings query with the guard in the same statement: `... WHERE NOT pg_is_in_recovery() OR pg_last_wal_replay_lsn() >= :minLsn`, or a guard query on the same connection inside one read-only transaction. If the guard fails, the request computes from `primaryAvailabilityRepository` and still writes the cache. The relay gate also requires `pg_is_in_recovery() = true` on its connection. When it is false, the gate is skipped and only the LSN guard protects readers. The gate must never rely on rows that cleanup can delete: CLN jobs delete only rows with `processed_at` or `failed_at` set.

### H4: HIGH. Two DataSources and two Redis factories break Boot auto-config and pull the replica into readiness (AD-3 vs AD-7)

**Unit A** follows AD-3: two `JdbcClient` beans, `@Primary` on `primaryJdbcClient`, each "on its own Hikari pool", so two `DataSource` beans. **Unit B** follows AD-7 and leaves the readiness group as `readinessState,db,redis`.

Two things go wrong:
- **Readiness.** Spring Boot's `db` contributor is a composite over **all** `DataSource` beans, and `redis` is a composite over all `RedisConnectionFactory` beans. A replica that is down makes `db` DOWN, readiness fails, and nginx loses every API instance. AD-7 is broken by following AD-3.
- **Auto-config.** `@Primary` on a `JdbcClient` does nothing for Flyway, JPA (`ddl-auto: validate` in server-api), Spring Batch, ShedLock, `spring.sql.init`, `DataSourceTransactionManager` or `JdbcTemplateAutoConfiguration`. Each of them needs a single or `@Primary` `DataSource`. `JdbcRedisHeartbeatProbe` injects `JdbcTemplate`, and `RedisAvailabilityCache` / `RedisAvailabilityVersionStore` inject an unqualified `StringRedisTemplate`. With two factories and no primary, Boot's `StringRedisTemplate` backs off, so startup either fails or picks the wrong node. The spine does not say whether scheduler-batch defines both Redis factories.

**Proposed rule (new AD-3b):**
> **AD-3b: Primary wins every default; the replica is never a default candidate.** Each app defines `primaryDataSource` (`@Primary`) and `replicaDataSource` (`@Bean(defaultCandidate = false)`). Flyway, JPA, Batch, ShedLock, sql-init, the transaction manager, `JdbcTemplate` and the heartbeat probe bind to the primary only. The `db` health contributor covers `primaryDataSource` only. The replica is exposed as `dbReplica`, outside the readiness and liveness groups. Redis follows the same rule: `masterRedisConnectionFactory` is `@Primary` and backs the default `StringRedisTemplate`. `replicaRedisConnectionFactory` and `replicaStringRedisTemplate` are `defaultCandidate = false`, exist **only in server-api**, and appear in health as `redisReplica`, outside readiness. scheduler-batch defines exactly one Redis factory (master, `readFrom=UPSTREAM`). Bean names live in `DataSourceNames` / `RedisNames` constants classes.

### H5: HIGH/MEDIUM. Replica-preferred slot reads defeat the rebuild lock, and replica promote rewinds versions

**Unit A** (cache builder) follows AD-6 to the letter: "Only slot-list GET/MGET → replica template". In `RedisAvailabilityCache.slots()` that covers all three `readCached` calls:
1. the first read,
2. the double-check after taking the lock,
3. the lock-wait poll loop.

**Unit B** (Redis ops) follows AD-1/AD-6: an async replica with default `replica-serve-stale-data yes`.

- **Lag versus `lock-wait: 100ms`.** The lock owner writes the slots to the **master**. Waiters poll the **replica** every 10 ms for 100 ms. Any replica lag near 100 ms (normal under load or a fork for full sync) means every waiter times out and computes from PG without writing. The stampede protection is gone exactly at peak load.
- **Broken replication link.** The replica stays "up", so `REPLICA_PREFERRED` keeps choosing it and serves stale data with no new keys. Every slot key written after the break is a permanent miss. Each request takes the lock (now free), double-checks the replica (miss), and rebuilds. The cache hit ratio drops to about 0 with no error and no fail-open signal, and all reads hit PG.
- **Promote rewinds versions.** `REPLICAOF NO ONE` on a replica that missed the last `INCR`s gives version `v` while `slots:...:v` (built before the booking) still exist on it. Stale hits follow until TTL. A master restart (no persistence, AD-1) resets versions to absent=0 while the replica can still serve old `...:v0` keys until the full resync.
- **Command-based routing.** The current code reads versions with `MGET` (`readVersions` → `multiGet`). A builder who routes "by command" (all `MGET` → replica) reads versions from the replica, which is the stale-version case that AD-6 forbids. The rule text says "GET" for versions and "GET/MGET" for slots, which invites this.

**Proposed rule (tighten AD-6):**
> **AD-6a: Route by key family, and only the first read goes to the replica.** The replica template may serve **only** the first slot-key read of a request. A replica miss is re-read once on the master before the request takes the rebuild lock. The post-lock double-check and the lock-wait poll loop read the **master only**. Version keys (`avail:ver:*`), lock keys and `avail:minlsn:*` are always master, whatever the command (`GET`, `MGET`, `EVAL`). The replica runs `replica-serve-stale-data no`, so a broken link gives `MASTERDOWN`, which the port treats as a miss and re-reads on the master. It does not trip `redisOff`. Every Redis master change (promote or restart) is followed by `FLUSHALL` on the new master before apps reconnect. This is a runbook step in AD-2, and it is safe because Redis is cache-only.

### H6: MEDIUM. Pool sizing: fallback piles every pool onto the primary, and standby settings must match

AD-3 gives each instance two Hikari pools (default 10 + 10). AD-7 says replica loss "falls back to primary", which means **every** replica pool reconnects to the primary. Count at the documented scale: server-api ×3 plus scheduler-batch ×2 (`docker-compose.scale.yml`) = 5 × 20 = 100 connections, plus the simulator, plus `psql`. PG's default `max_connections=100` minus 3 superuser-reserved slots = 97. Bookings fail with `too many clients` exactly when the replica is down, which breaks AD-7's "degrades, never fails". There is also a hard Postgres rule: a hot standby **refuses to start** if its `max_connections` (or `max_worker_processes`, `max_wal_senders`, `max_prepared_transactions`, `max_locks_per_transaction`) is lower than the primary's. A builder who tunes only the primary's `max_connections` breaks the replica.

**Proposed rule (new AD-3c):**
> **AD-3c: Pool budget assumes every pool lands on one node.** `maximum-pool-size` is set explicitly per role in `application.yml`: primary 10 and replica 6 for server-api; primary 6 and replica 2 for scheduler-batch. Then Σ(all pools × max instances in any compose file) + 10 (simulator, ops) ≤ `max_connections − superuser_reserved_connections` on **each** node. `max_connections` and the other standby-critical GUCs come from one shared config file mounted into both Postgres services, never set per service. Hikari `maxLifetime` is 5 min on the replica pool, so connections that fell back move back to the replica soon after it recovers.

### H7: MEDIUM. Where the gate waits: inside TX B it fights `idle_in_transaction_session_timeout`; per row it can stall the tick

**Unit A** (relay) follows AD-5 and calls the gate port from `work()` inside TX B, after `relock()`, while it holds the row lock. **Unit B** (config) follows CLAUDE.md §3 with `gate-timeout: 30s` in `@ConfigurationProperties`.

TX B's primary session sits idle in a transaction while the gate polls the replica on another connection. `connection-init-sql: SET idle_in_transaction_session_timeout = '30s'` (scheduler-batch `application.yml`) kills it, so `markProcessed` / `scheduleRetry` fail and the row is recovered only by the next claim's exhaustion path. The spine also does not say whether a gate timeout stops the tick (like a Redis error, `return false`) or moves on. If it moves on, one tick can take `batch-size × timeout` = 100 × T. Rows are ordered by `created_at` (transaction **start** time), not commit LSN, so "row N visible ⇒ earlier rows visible" cannot be used to batch the wait. Last, gate timeouts take retries and end in `FAILED`, which raises ERROR operator alerts for plain replica lag during every replica restart.

**Proposed rule (tighten AD-5):**
> **AD-5b: The gate runs between TX A and TX B, never inside a transaction.** `gate-timeout` must be < `idle_in_transaction_session_timeout` and < `retry-delay × (max-retries + 1)`; this is checked at startup, like the existing property validation. On gate timeout the row goes through `scheduleRetry` and **the tick stops**, the same as a Redis error. A gate timeout is logged as `WARN outbox.gate.lag`, separately from Redis errors, so an operator can tell "replica behind" from "relay broken" before the row reaches `FAILED`.

### H8: LOW. Two owners for the outbox read and stale env names in non-Java units

- AD-5 says "a new outbox replica-read port" but does not name it. Builder A adds `isVisible(id)` to `OutboxEventRepository`, so its impl needs both clients, which breaks AD-3's "exactly one". Builder B creates a new port. Both readings fit the text.
- The Conventions section retires `DB_HOST`, but `loadtest/simulator/simulate.py` and `scripts/run-*.sh` read `DB_HOST` with a single host. After H2/AD-2 the simulator keeps writing to whichever host it was given, so the "no config change on promote" promise does not cover it.

**Proposed rule:**
> **AD-4b:** The gate port is `repository/outbox/OutboxVisibilityRepository` with impl `impl/JdbcOutboxVisibilityRepository` (replica). `OutboxEventRepository` stays primary-only and gets no replica methods. The simulator and scripts use libpq multi-host `host=pg-a,pg-b target_session_attrs=read-write`, built from `DB_HOSTS`. No component reads `DB_HOST`.

### H9: LOW. Hot standby query cancellation shows up as a 500

A replica query can be cancelled by WAL replay (`40001 canceling statement due to conflict with recovery`) once it delays replay longer than `max_standby_streaming_delay`. Availability queries are short, so this is rare, but a vacuum burst on the primary under load is enough. Today it would surface as a 500 on `GET /availability`.

**Proposed rule (add to AD-4):**
> A replica read that fails with SQLSTATE `40001` / `40P01` is retried once on the primary bean of the same interface (AD-4a). The replica runs `hot_standby_feedback = on`.

## Checked and found closed (for traceability)

| Probe | Result |
|---|---|
| Cancellation events vs gate | `UPDATE status` + outbox insert in one TX (simulator, booking path). The gate's atomicity argument holds. Wrong behaviour comes only through H1 (stale overlap on rebook). |
| Outbox cleanup deletes rows before the gate | Not possible today: CLN-01..03 delete only `PROCESSED`/`FAILED` rows, and the gate runs before terminal state. Pinned in AD-5a. |
| Version read before PG read | Safe on one monotonic replica node. Broken only by H3 (mixed nodes per pool). |
| Heartbeat probe | Already primary + master by injection, but it relies on Boot defaults. Covered by AD-3b. |
| ShedLock / Batch metadata | Primary only by AD-4, but only if Boot binds them to `@Primary DataSource`. Covered by AD-3b. |
| Lettuce role re-detection after promote | Still an open question. H2/AD-2a and the `FLUSHALL` step in AD-6a make the answer matter less: restarting the apps after a Redis promote becomes the runbook default. |

## Summary of proposed AD changes

| New / tightened | Closes | Severity |
|---|---|---|
| AD-4a role per bean, dual beans for `AvailabilityRepository`, ARC-09 | H1 | Critical |
| AD-2a roles are runtime state, role-agnostic entrypoint, `demoted` marker | H2 | Critical |
| AD-5a LSN stamp + guarded rebuild; gate requires `pg_is_in_recovery()` | H3 | High |
| AD-3b replica never a default candidate; `dbReplica`/`redisReplica` outside readiness | H4 | High |
| AD-6a route by key family; master for double-check and polling; `FLUSHALL` on master change | H5 | High/Medium |
| AD-3c pool budget with all pools on one node; shared GUC file | H6 | Medium |
| AD-5b gate outside TX, timeout bounds, tick stops, distinct WARN | H7 | Medium |
| AD-4b name the gate port; simulator multi-host | H8 | Low |
| AD-4 addendum: 40001 retry on primary, `hot_standby_feedback` | H9 | Low |
