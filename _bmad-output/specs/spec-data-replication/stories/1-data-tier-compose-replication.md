---
title: 'Data tier compose with Postgres and Redis replication'
type: 'feature'
created: '2026-11-02'
status: 'done'
baseline_commit: 'c55d4045ce15b09d62ecdf131f20e05ec15b1862'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/specs/spec-data-replication/SPEC.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-keyloop-replication-2026-11-01/ARCHITECTURE-SPINE.md'
  - '{project-root}/CLAUDE.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Compose runs one `postgres` and one `redis`, so the scale demo has no replica to read from or fail over to (SPEC CAP-1, CAP-2, CAP-8).

**Approach:** Replace them with `postgres-1`/`postgres-2` (async streaming, slot) and `redis-1`/`redis-2` (cache-only replica); the role comes from `.env` (`PG_PRIMARY`, `REDIS_MASTER`). Every consumer switches to node lists `DB_HOSTS`/`REDIS_NODES`. Apps still use primary/master only (spine AD-1, AD-8, AD-9).

## Boundaries & Constraints

**Always:** official `postgres:18-alpine`/`redis:8-alpine`; one bootstrap script decides a node's role from `PG_PRIMARY`/`REDIS_MASTER` vs its own `NODE_NAME`; no `restart:` on data services; Redis `--appendonly no --save ""`; JDBC URLs `targetServerType=primary`; Redis via `spring.data.redis.masterreplica.nodes` + `lettuce.read-from: upstream` (keep `timeout: 1s`); shell tools target `${PG_PRIMARY:-postgres-1}` / `${REDIS_MASTER:-redis-1}`.

**Never:** replica DataSource or replica Redis template (stories 2–4); failover scripts (story 5); Bitnami images; Sentinel/Patroni; changes to Java code or tests beyond config.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh stack | empty volumes, `up` | primary initdb + init scripts; replica `pg_basebackup -R -C -S replica_slot` then streams; redis-2 `replicaof redis-1` | replica waits for primary `pg_isready` (bounded), exits non-zero on timeout |
| Replica restart | replica volume has data | starts as standby, no basebackup | — |
| Replica re-seed | replica volume deleted, slot still on primary | stale inactive `replica_slot` dropped, recreated by basebackup | active slot → fail loudly |
| Role check | healthcheck on each node | primary: `pg_isready` + not in recovery; replica: `pg_isready` + in recovery | unhealthy if role mismatches `.env` |
| Tests | Testcontainers `@ServiceConnection` | standalone Redis/Postgres, unchanged | — |

</frozen-after-approval>

## Code Map

- `docker-compose.yml` -- data services, `x-app-env` (`DB_HOST`/`REDIS_HOST` today), volumes `pg-data`/`redis-data`, app `depends_on`.
- `docker-compose.loadtest.yml:60-85` -- `simulator` env `DB_HOST` + `depends_on: postgres`.
- `infra/postgres/init/01-extensions.sql` -- keep; add replication init next to it.
- `server-api/src/main/resources/application.yml:13-29`, `scheduler-batch/src/main/resources/application.yml:7-28` -- datasource URL + `spring.data.redis.host/port`; batch keeps `currentSchema=batch,public` and Hikari init-sql.
- `infra/k8s/server-api/deployment.yaml:37-46` -- env `DB_HOST`/`DB_PORT`/`REDIS_HOST`/`REDIS_PORT`.
- `loadtest/simulator/simulate.py:119` -- libpq DSN from `DB_HOST`/`DB_PORT`.
- `scripts/lib/run-app.sh:61-67`, `scripts/run-scheduler-batch.sh:17`, `seeding/seed.sh:13`, `loadtest/run-ramp.sh:38,53-54,108,138-139`, `loadtest/verify/*.sql` header comments, `README.md:54,118,130` -- hard-coded service names `postgres`/`redis`.
- `.env.example` -- env catalog.
- Tests use `@ServiceConnection` (`*/support/TestcontainersConfig.java`); Boot 4.1.2 derives the Redis mode from connection details, so container standalone details override the `masterreplica` property -- no test change.

## Tasks & Acceptance

**Execution:**
- [x] `infra/postgres/node-entrypoint.sh` -- new wrapper: if `NODE_NAME != PG_PRIMARY` and `PGDATA` empty → wait for primary, drop inactive stale slot, `su-exec postgres pg_basebackup -h $PG_PRIMARY -U $PG_REPL_USER -D $PGDATA -R -X stream -C -S replica_slot`; then `exec docker-entrypoint.sh "$@"`.
- [x] `infra/postgres/healthcheck.sh` -- role-aware check per matrix.
- [x] `infra/postgres/init/02-replication.sh` -- create `REPLICATION LOGIN` role from env (idempotent) and append `host replication $PG_REPL_USER all scram-sha-256` to `pg_hba.conf`.
- [x] `infra/redis/node-entrypoint.sh` -- add `--replicaof $REDIS_MASTER 6379 --replica-serve-stale-data no` unless `NODE_NAME == REDIS_MASTER`.
- [x] `docker-compose.yml` -- 4 data services, shared args `-c wal_level=replica -c max_connections=200 -c hot_standby_feedback=on -c max_wal_senders=5 -c max_replication_slots=5`; volumes `pg-data-1`/`pg-data-2`, no redis volume; host ports 5432/5433, 6379/6380; app env `DB_HOSTS`/`REDIS_NODES`; apps depend on all four healthy.
- [x] `docker-compose.loadtest.yml` -- simulator `DB_HOSTS` + depends on `postgres-1`/`postgres-2`.
- [ ] both `application.yml` -- URL `jdbc:postgresql://${DB_HOSTS:localhost:5432}/…targetServerType=primary`; Redis `masterreplica.nodes: ${REDIS_NODES:localhost:6379}`, `lettuce.read-from: upstream`.
- [x] `loadtest/simulator/simulate.py` -- parse `DB_HOSTS` into libpq `host=`/`port=` lists + `target_session_attrs=read-write`.
- [x] `infra/k8s/server-api/deployment.yaml` -- `DB_HOSTS=postgres:5432`, `REDIS_NODES=redis:6379`.
- [ ] shell/doc consumers in Code Map -- use `${PG_PRIMARY:-postgres-1}` / `${REDIS_MASTER:-redis-1}`; `run-app.sh` exports node lists with localhost ports and starts the 4 services.
- [x] `.env.example`, `README.md` -- new vars (`PG_PRIMARY`, `REDIS_MASTER`, `PG_REPL_USER`, `PG_REPL_PASSWORD`, replica ports); topology section; one-time migration (`docker compose down -v` on the old checkout or `docker volume rm keyloop-scheduler_pg-data keyloop-scheduler_redis-data`, then re-seed).

**Acceptance Criteria:**
- Given an empty stack, when `docker compose up -d --wait`, then all services are healthy and `pg_stat_replication` on `postgres-1` shows one `streaming` row with slot `replica_slot`.
- Given the stack is up, when a row is inserted on `postgres-1`, then it is readable on `postgres-2`, and a write on `postgres-2` fails with read-only error.
- Given the stack is up, when `SET k v` on `redis-1`, then `GET k` on `redis-2` returns `v` and `SET` on `redis-2` returns `READONLY`.
- Given the change, when grepping the repo (excluding `planing/`, worktrees, build output), then `DB_HOST`, `DB_PORT`, `REDIS_HOST` and `compose exec -T postgres|redis` no longer appear.
- Given the stack is up, when calling `GET /api/v1/availability` and booking via nginx, then both work as before.

## Design Notes

- The slot is created by the replica (`pg_basebackup -C`), not by primary init as AD-8 says: slots do not replicate, so after a failover the new primary has none and a re-seed would fail. Same invariant, safer placement.
- Apps depend on all four services healthy (not "primary only" from AD-8): compose `depends_on` cannot follow the env-chosen primary. Runtime replica loss is still story 3/4 territory.
- Role/pg_hba reach the replica through `pg_basebackup`, so a promoted node already has them.

## Verification

**Commands:**
- `./gradlew build` -- expected: green, tests unchanged.
- `docker compose down -v && docker compose up -d --build --wait` -- expected: all healthy.
- `docker compose exec -T postgres-1 psql -U scheduler -d scheduler -tAc "select state, slot_name from pg_stat_replication join pg_replication_slots on active_pid = pid"` -- expected: `streaming|replica_slot`.
- `docker compose exec -T redis-1 redis-cli INFO replication` -- expected: `connected_slaves:1`.
- `seeding/seed.sh` then smoke loadtest profile -- expected: passes.

## Implementation Notes

- `gosu` instead of `su-exec` in `infra/postgres/node-entrypoint.sh`: `postgres:18-alpine` ships only `gosu`.
- Failed `pg_basebackup` empties `PGDATA` so the next start cannot come up as a broken standby; primary wait is `PG_PRIMARY_WAIT_SECONDS` (default 120s).
- Healthchecks use TCP 127.0.0.1 so a node is not healthy while init scripts run; Redis replica also needs `master_link_status:up`.
- Host-port vars renamed to `POSTGRES_1_PORT`/`POSTGRES_2_PORT`/`REDIS_1_PORT`/`REDIS_2_PORT` (old `POSTGRES_PORT`/`REDIS_PORT` removed from `.env.example`; a local `.env` that still has them keeps working via defaults).
- `seed.sh`/`run-ramp.sh` read `PG_PRIMARY`/`REDIS_MASTER` from env or `.env`, so they follow a failover (story 5).
- Matrix audit: `scripts/verify-data-tier.sh` (default 13 rows non-destructive; `--destructive` adds restart, re-seed, stale-slot drop, active-slot refusal). Both modes PASS; Testcontainers row covered by `./gradlew build`.
- Known risks left open: `max_slot_wal_keep_size` unset (WAL grows while the replica is down); replication password stored in replica `postgresql.auto.conf` by `-R`.

## Spec Change Log

## Review Triage Log

| # | Layer | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|---|
| 1 | blind, edge | Node not in `PG_PRIMARY` with data but no `standby.signal` starts writable after an env flip; designated primary with `standby.signal` silently stays standby | medium | `node-entrypoint.sh` only branches on empty PGDATA; pgjdbc `targetServerType=primary` would pick the writable old node | patch |
| 2 | blind | Comments say a failover "only flips PG_PRIMARY/REDIS_MASTER" | low | promote is not done by the entrypoint; story 5 owns it; comment misleads | patch (with #1) |
| 3 | blind | Apps wait for all four nodes healthy | low | spec Design Notes chose this; fix edits this build's spec | reject |
| 4 | blind | Replica exits after 120s wait, no restart policy | low | AD-1 forbids restart on data services; role is env-chosen so no per-role policy | reject |
| 5 | blind | Slot retains WAL without bound | medium | no `max_slot_wal_keep_size` in `x-postgres` command | patch |
| 6 | blind | Migration note misses orphan `postgres`/`redis` containers holding 5432/6379 | medium | old containers keep host ports, `postgres-1`/`redis-1` fail to bind | patch |
| 7 | blind, verification-gap | Multi-host JDBC / masterreplica config not exercised by any automated check | medium | `@ServiceConnection` overrides URL and Redis mode (verified in Boot 4.1.2); defaults hide a missing `targetServerType` | defer (story 5 role-flip check) |
| 8 | blind | REL-05 fail-open untested with static master/replica | maybe-false | needs an IT with the master stopped under masterreplica mode | defer (unverified medium) |
| 9 | blind, edge | `.env` parsing copied and fragile (quotes, CRLF, comments) | low | `.env.example` values are unquoted; shared helper is new complexity | reject |
| 10 | blind | k8s gets no replication | false | SPEC non-goal excludes k8s data tier | reject |
| 11 | blind | Weak default replication password | low | matches existing dev defaults (`POSTGRES_PASSWORD=scheduler`) | reject |
| 12 | blind | Failed basebackup leaves stale slot | low | next start drops inactive slot; WAL bounded by #5 | reject |
| 13 | blind, edge | verify script: destructive steps abort under `set -e` without summary; read-only check depends on probe table | low | `docker volume rm`/`restart`/`rm -sf` lack `|| fail`; probe table may be absent | patch |
| 14 | blind, edge | PG replica healthcheck passes when in recovery but not streaming; no connect timeout | medium | `healthcheck.sh` checks only `pg_is_in_recovery()`; Redis check already requires link up | patch |
| 15 | edge | Re-seed refused while walsender of a killed replica still shows the slot active | maybe-false | depends on TCP teardown timing on container kill; would be low | reject |
| 16 | edge | Container killed mid-`pg_basebackup` leaves PGDATA with `PG_VERSION`, next start runs broken standby | medium | failure cleanup only runs when basebackup exits non-zero | patch |
| 17 | edge | Non-numeric `PG_PRIMARY_WAIT_SECONDS` loops forever | low | unlikely; adds validation branch | reject |
| 18 | edge | `start_period` may be short for large base backups | low | demo dataset is small; healthcheck still retries 50s | reject |
| 19 | edge | verify refusal test may hang if slot inactive | low | dev-only script; macOS lacks `timeout` | reject |
| 20 | edge | Empty `DB_HOSTS` → libpq local socket | low | unlikely; compose always sets it | reject |
| 21 | edge | Custom `POSTGRES_PORT`/`REDIS_PORT` in old `.env` silently ignored | low | direct fallback `${POSTGRES_1_PORT:-${POSTGRES_PORT:-5432}}` | patch |
| 22 | edge | README verification commands hard-code `postgres-1`/`redis-1` | low | spec says shell tools target `${PG_PRIMARY}`/`${REDIS_MASTER}` | patch |
