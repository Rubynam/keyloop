---
reviews: ARCHITECTURE-SPINE.md (keyloop-replication, draft, 2026-11-01)
lens: good-spine rubric
date: 2026-11-01
verdict: NOT READY — sound paradigm, but key wiring and ops points are left open, so two builders could still make different choices
---

# Rubric review — keyloop replication spine

## Verdict

The paradigm is right and fits the codebase: replica routing is chosen per port implementation, there is no routing DataSource, layering stays as it is, and failover is manual. AD-1, AD-2, AD-4 and AD-6 are well aimed. But the spine is **not ready as a build substrate**:

- It names `JdbcClient` beans but leaves the `DataSource` / health / Redis-factory wiring open. In Spring Boot that wiring is exactly where two builders would diverge, and where the current code (ShedLock, heartbeat, Flyway, JPA, readiness, the REL-05 timeout) silently changes behaviour.
- The AD-5 visibility gate can pass without really checking the replica.
- Deployment, init scripts, secrets and the brownfield migration of existing volumes and env vars are mostly undecided.

Fix the 4 blockers below and it can pass.

## Scorecard

| Criterion | Result | Notes |
|---|---|---|
| Fixes the real divergence points, misses none | Partial | Routing per port: done well. Missing: DataSource `@Primary` + property shape, health indicators, Redis factory config (timeout), gate placement/pool, replication slot vs wal_keep, replica query-cancel handling, test wiring |
| Every AD Rule is enforceable and prevents its divergence | Partial | AD-3/4/6 have no mechanical check (no ArchUnit rule, no test named). AD-5 can be bypassed by `preferSecondary` fallback. AD-7 is contradicted by Boot defaults |
| Nothing under Deferred lets two units diverge | Mostly | "More than one replica" and "k8s" each hide a coupling (see F7, F8) |
| Ratifies, does not contradict, brownfield | Mostly | Layering, Flyway ownership, ShedLock on primary, fail-open REL-05 and relay retry are named correctly. But "`DB_HOST` retired" breaks k8s, the simulator, `scripts/lib/run-app.sh` and the loadtest overlay. The gate wait inside TX B is in tension with `idle_in_transaction_session_timeout=30s` (REL-06) and the tick-stop rule |
| Every owned dimension decided / deferred / open | No | Deployment/env is partial. Init scripts, secrets (replication role), pg_hba, volumes, startup ordering, runbook location, test strategy and observability-on-gate are not covered |

## Findings

### F1 — BLOCKER — The DataSource layer is undecided; `@Primary` on `JdbcClient` is not enough

**Where:** AD-3. **Code:** `SchedulingConfig.lockProvider(DataSource)`, `JdbcRedisHeartbeatProbe(JdbcTemplate …)`, Flyway + `spring-boot-starter-data-jpa` in server-api, `spring.batch.jdbc.initialize-schema`, `spring.sql.init` (ShedLock `schema.sql`).

Once there are two Hikari pools there are two `DataSource` beans. Boot's DataSource auto-config backs off. Then Flyway, JPA's `EntityManagerFactory`, `DataSourceTransactionManager`, the auto `JdbcTemplate` (used by the heartbeat), Batch's job repository, SQL init and the ShedLock `LockProvider(DataSource)` all need **one unambiguous DataSource**. They fail at boot, or worse, pick the wrong one. AD-4 says these stay "primary only", but no rule makes that true. The property layout is also open: `spring.datasource.*` + `scheduler.datasource.replica.*`, or `spring.datasource.primary/replica`? Builders on the two apps will pick differently. Batch-specific Hikari settings also live there today: `currentSchema=batch,public` and `connection-init-sql: idle_in_transaction_session_timeout` (REL-06).

**Fix:** Add to AD-3:
- "Each app defines `primaryDataSource` (`@Primary`, from `spring.datasource.*`, unchanged keys) and `replicaDataSource` (from `scheduler.datasource.replica.*`, built with `DataSourceProperties`/`HikariDataSource`)."
- "Flyway, JPA, the transaction manager, `JdbcTemplate`, Batch, SQL init and ShedLock bind to the `@Primary` DataSource by default. No `@FlywayDataSource`/`@BatchDataSource` points at the replica."
- "The scheduler-batch replica URL keeps `currentSchema=batch,public`. The replica pool is read-only (`hikari.read-only=true`) and needs no `connection-init-sql`."
- Name the constants class (`DataSourceNames` in `constant/`) per the Conventions line.

### F2 — BLOCKER — AD-7 is contradicted by Spring Boot health defaults

**Where:** AD-7. **Code:** both `application.yml` files: `readiness.include: readinessState,db,redis`.

With two DataSources, the `db` indicator becomes a composite that pings **both** pools. With two `LettuceConnectionFactory` beans, `redis` checks both. So a replica outage fails readiness and nginx/k8s drop the API — the exact divergence AD-7 says it prevents. The rule says what readiness should check but not how. One builder will exclude by name, another will leave the defaults.

**Fix:** Make the rule concrete. Readiness includes `readinessState,db.primaryDataSource,redis.<masterFactoryName>` (or disables the replica contributors with `management.health.db.ignore-routing-data-sources`-style config / a custom `HealthContributor` filter). Add a liveness-neutral `replica` health group for visibility. Add an IT: stop the replica container → `/actuator/health/readiness` stays `UP`.

### F3 — BLOCKER — The AD-5 gate can pass without really checking, and its placement is undecided

**Where:** AD-5, AD-3 (`preferSecondary`). **Code:** `OutboxRelayServiceImpl.work()` runs everything in TX B. `tick()` stops on `false`. Hikari connections keep the host they first reached.

1. **Gate that passes too easily.** If the relay's replica pool fell back to the primary (replica was down at connect time), the gate "sees" the row on the primary and passes at once. Meanwhile server-api's read pool can open fresh connections to the recovered, still-lagging replica. The INCR then lands before the replica has replayed the row, which is the stale pin AD-5 exists to stop. `preferSecondary` is right for reads but wrong for a visibility check.
2. **Placement.** "For each claimed row … bounded wait" does not say *where* the wait sits. Inside TX B it holds the outbox row lock and a primary connection idle for up to the timeout. That runs into `idle_in_transaction_session_timeout=30s` (REL-06) and holds pool capacity.
3. **Retry semantics.** It is not said whether a gate timeout is a "runtime error" that stops the tick (current `return false`), and whether it uses the Redis retry budget (3 × 2 s). With the current policy, any lag above about timeout + 6 s turns every pending event FAILED and the cache stays stale until TTL.

**Fix:** Add to AD-5:
- "The gate uses its own read path with `targetServerType=secondary` (no fallback). If the replica is unreachable, the gate passes and logs WARN, because readers fall back to the primary too. Residual risk: up to slot TTL (≤60 s) after replica recovery, recorded as accepted."
- "The gate runs between TX A and TX B, outside any transaction. A timeout calls `scheduleRetry` / FAILED through the existing §4.3 path. It counts as a try and **does** stop the tick."
- "`replica-visibility-timeout` and `replica-poll-interval` go in `OutboxRelayProperties` (prefix `scheduler.outbox.relay`), are checked at startup with the existing validation, and `timeout < retry-delay × …`." State the bound.
- Name the port: `repository/outbox/OutboxReplicaProbe` + `impl/JdbcOutboxReplicaProbe` (CLAUDE.md §1 layout).

### F4 — BLOCKER — Postgres replica bootstrap, init scripts, secrets and brownfield volumes are undecided

**Where:** AD-1, Conventions. **Code:** `infra/postgres/init/01-extensions.sql` (runs only on an empty volume), volume `pg-data`, `.env.example`.

Open points that two builders (or two runs) would settle differently:
- **Replication role.** Name and password env vars (`POSTGRES_REPLICATION_USER` / `POSTGRES_REPLICATION_PASSWORD`?). Created by an init script (not Flyway — Flyway owns the domain schema).
- **pg_hba.** The official image adds `host all all all scram-sha-256`. `all` does **not** match `replication`, so a `host replication <role> all scram-sha-256` line must come from somewhere: an init script that appends it, or a mounted `pg_hba.conf`.
- **Brownfield volume.** Existing dev `pg-data` volumes are already initialised, so a new init script never runs on them. The spine must say either "recreate volumes (`down -v`)" or "provide an idempotent `scripts/db/create-replication-role.sh`". It must also say which volume names are kept (`pg-data` → primary, so dev data survives?) and the new ones (`pg-replica-data`).
- **Replica entrypoint.** A wrapper script on `postgres-replica`: wait for the primary, run `pg_basebackup -R` (plus `-C -S <slot>`?) only if PGDATA is empty, then `exec docker-entrypoint.sh postgres`. Its path is not named (`infra/postgres/replica/entrypoint.sh`?).
- **WAL retention.** A physical replication slot (replica never breaks, but WAL can grow on the primary while the replica is down) vs `wal_keep_size` (bounded disk, but the replica may need a re-seed). This must be decided; it changes AD-2's re-seed runbook.
- **Startup ordering.** `server-api`/`scheduler-batch` `depends_on` only `postgres-primary` + `redis-master` (healthy). The replica must not be required (matches AD-7). Healthchecks for the replicas: `pg_isready` and `redis-cli ping` / `role`.

**Fix:** Add an AD-8 "Data-tier bootstrap" with the bullets above decided, and list the new env vars in Conventions and `.env.example`.

### F5 — MAJOR — The Redis factory config loses REL-05's 1 s timeout; AD-6 cannot be enforced

**Where:** AD-6. **Code:** `spring.data.redis.timeout: 1s` (REL-05). `RedisAvailabilityCache` takes one `StringRedisTemplate`. The auto `StringRedisTemplate` needs a single candidate factory.

Hand-built `LettuceConnectionFactory`s ignore `spring.data.redis.*`. The 1 s command timeout that makes fail-open fast goes away unless it is restated. Also, one factory must be `@Primary`, or the auto `StringRedisTemplate`, the redis health indicator and the batch heartbeat/version store become ambiguous. Inside `RedisAvailabilityCache` it is not specified which calls use which template:
- `readVersions()` is an MGET too, but it must go to the master.
- The post-lock re-check (`readCached(toBuild…)`) and the lock-wait poll loop read slot keys. If they hit the replica, a value the owner just wrote on the master is missed. The waiter then falls through to PostgreSQL, which weakens the stampede protection the lock exists for.

**Fix:** Add to AD-6:
- "Both factories are built from `RedisProperties` (host/port/timeout/client name). The master factory is `@Primary`."
- "Only the *first* cache-hit read (`readCached` before taking the lock) uses the replica template. The re-check after taking the lock, the poll loop, the version MGET and all writes use the master."
- "Any `DataAccessException` from either template triggers the existing request-level fail-open."
- Enforce it with a unit test that uses two mocked templates and checks which template each step calls, not with prose.

### F6 — MAJOR — No mechanical check for AD-3 / AD-4

**Where:** AD-3, AD-4. **Code:** `ArchitectureTest` ARC-01…08 (both apps).

"Only two callers use `replicaJdbcClient`" is the most important rule in the spine, and it is pure prose. A builder who adds a new repository and copies `JdbcAvailabilityRepository` inherits the replica qualifier.

**Fix:** Add ARC-09 to both `ArchitectureTest`s:
- Only `JdbcAvailabilityRepository` (server-api) and `JdbcOutboxReplicaProbe` (batch) may have a constructor parameter qualified with the replica bean name.
- No `*ServiceImpl` may reference `DataSourceNames.REPLICA_*`.
- Only `RedisAvailabilityCache` may inject the replica Redis template.

### F7 — MINOR — Brownfield env-var retirement contradicts files that are kept

**Where:** Conventions ("`DB_HOST`/`REDIS_HOST` are retired"), Deferred (k8s). **Code:**
- `infra/k8s/server-api/deployment.yaml` uses `DB_HOST` / `REDIS_HOST`.
- `loadtest/simulator/simulate.py:119` uses `DB_HOST`. The simulator is bound by AD-4 as primary-only.
- `scripts/lib/run-app.sh:61-62` exports them.
- `docker-compose.loadtest.yml:66,77` uses `DB_HOST: postgres` and `depends_on: postgres`.
- `POSTGRES_PORT` / `REDIS_PORT` are host-port vars in `.env.example`, but `REDIS_PORT` is also the container port in `x-app-env`.

**Fix:** Either keep `DB_HOST`/`REDIS_HOST` as aliases with the primary/master as default (`${DB_PRIMARY_HOST:${DB_HOST:localhost}}`), so k8s keeps working while it is deferred, or list every file above as in scope. Rename the host-port vars `POSTGRES_PRIMARY_PORT` / `POSTGRES_REPLICA_PORT` / `REDIS_MASTER_PORT` / `REDIS_REPLICA_PORT` to end the clash.

### F8 — MINOR — Deferred items that hide couplings

- **"More than one replica".** AD-5 checks *one* replica. With two, readers can hit the one that lags. Add a line: "The gate must cover every replica readers can reach; adding a replica reopens AD-5."
- **Redis promote.** After `REPLICAOF NO ONE`, version keys can roll back (an INCR that never replicated). Readers can then hit a `:v{old}:` slot list that is still live (≤60 s) — a real stale hit, not a miss. Add to the AD-2 runbook: "after a Redis promote, `FLUSHALL` (cache only) or restart apps", and link it to the existing Lettuce `ROLE` open question.

### F9 — MINOR — Replica query cancellation and test wiring not covered

- Hot-standby conflicts can cancel availability queries ("canceling statement due to conflict with recovery"). Decide: `hot_standby_feedback=on` on the replica, plus no app retry. Or let the error map to 5xx. Don't leave it to the builder.
- The ITs use Testcontainers (`support/TestcontainersConfig`, likely `@ServiceConnection`), which supplies **one** connection. The spine should say how tests run: replica properties default to the primary URL in the test profile, plus one replication IT on a two-node compose or Testcontainers network for AD-5 and AD-7.

## What is good (keep)

- The paradigm — routing per port implementation, no `AbstractRoutingDataSource` — keeps replica awareness inside `impl/`. It fits ARC-02/03 without change.
- AD-4's list of who uses the primary is complete and correct for the brownfield code: ShedLock, Batch metadata, Flyway, outbox claims, heartbeat, simulator.
- AD-5 correctly finds the real hazard of the version-key cache under lag. Keying the check on the outbox row id (same commit as the appointment) is the right signal.
- AD-2's split-brain order and AD-1's "official images, async, cache-only Redis" match the memlog decisions and the existing compose file.
- The Deferred note on `WAIT` is right: versioned keys turn Redis lag into a miss (apart from the promote case, F8).

## Minimum edits to reach "ready"

1. Make the AD-3 DataSource / `@Primary` / property-shape rule concrete (F1).
2. Make the AD-7 health-contributor rule concrete, with an IT (F2).
3. Fix the AD-5 gate pool, placement, retry semantics and property names (F3).
4. Add AD-8 for data-tier bootstrap: role, pg_hba, entrypoint, slot vs wal_keep, volumes, depends_on, secrets env (F4).
5. Make the AD-6 template mapping per call concrete, including the timeout (F5), and add ARC-09 (F6).
