# keyloop — Unified Service Scheduler

Keyloop technical assessment, Scenario A. Design notes: [ROADMAP.md](ROADMAP.md), [docs/](docs/).

## Modules

| Module | Role | Port |
|---|---|---|
| `nginx` | Load balancer, single entry point; discovers `server-api` replicas via Docker DNS ([Spec02](planing/Spec02.md)) | 8080 |
| `server-api` | REST API, owns the DB schema (Flyway), talks to PostgreSQL + Redis. 2 replicas by default, reached only through nginx | — |
| `loadtest` | Locust load tests, profile `loadtest` only ([Spec06](planing/Spec06.md)) | 8089 (UI) |
| `scheduler-batch` | Spring Batch worker jobs, scheduled with ShedLock (one run per tick across replicas) | 8081 (actuator) |

Stack: Java 25 · Spring Boot 4.1 · Gradle 9 (wrapper) · PostgreSQL 18 · Redis 8.

## Getting started

Requires Docker only (the Gradle wrapper downloads Gradle; the build runs inside Docker).

```bash
cp .env.example .env
docker compose up -d --build --wait
```

```bash
curl localhost:8080/api/v1/ping
curl localhost:8080/actuator/health
curl localhost:8081/actuator/health
docker compose logs -f scheduler-batch   # heartbeatJob every 5 min
```

### Load balancer and API replicas

`localhost:8080` is nginx. Every response carries `X-Upstream-Addr` (which replica answered) and `X-Request-Id`.

```bash
for i in $(seq 10); do curl -s -o /dev/null -D - localhost:8080/api/v1/ping | grep -i x-upstream; done
docker compose up -d --scale server-api=3 --no-recreate server-api   # nginx picks it up in ~5 s, no reload
docker compose up -d --scale server-api=2 --no-recreate server-api
```

Networks: `edge` (nginx only, published) · `lb-net` (internal, nginx ↔ server-api) · `data-net` (apps ↔ data nodes; nginx is not on it).

### Data tier: Postgres and Redis replication

Two nodes per store, same definition each. Which node is primary/master comes from `.env`, never from the service name.

| Service | Role (default `.env`) | Host port | Data |
|---|---|---|---|
| `postgres-1` | primary (`PG_PRIMARY=postgres-1`) | 5432 (`POSTGRES_1_PORT`) | volume `pg-data-1` |
| `postgres-2` | async streaming replica, slot `replica_slot` | 5433 (`POSTGRES_2_PORT`) | volume `pg-data-2` |
| `redis-1` | master (`REDIS_MASTER=redis-1`) | 6379 (`REDIS_1_PORT`) | none (cache only) |
| `redis-2` | replica, refuses reads while its master link is down | 6380 (`REDIS_2_PORT`) | none |

```
postgres-1 (primary) ──WAL stream, slot replica_slot──► postgres-2 (standby, read-only)
redis-1    (master)  ──replication───────────────────► redis-2    (replica, read-only)
apps: DB_HOSTS=postgres-1:5432,postgres-2:5432   REDIS_NODES=redis-1:6379,redis-2:6379
      → JDBC targetServerType=primary, Lettuce read-from upstream: all traffic on the writable node
```

- A replica with an empty volume copies the primary (`pg_basebackup`) on start; with data it starts as a standby.
- Healthchecks are role-aware: a node is unhealthy when its role does not match `.env`.
- Data services have no `restart:` policy on purpose (an auto-restarted old primary would accept writes).
- Shell tools target `${PG_PRIMARY:-postgres-1}` / `${REDIS_MASTER:-redis-1}`.
- `scripts/verify-data-tier.sh` checks the replication matrix on a running stack (PASS/FAIL per row); `--destructive` also restarts and re-seeds the Postgres replica.

```bash
docker compose exec -T "${PG_PRIMARY:-postgres-1}" psql -U scheduler -d scheduler -tAc \
  "select state, slot_name from pg_stat_replication join pg_replication_slots on active_pid = pid"   # streaming|replica_slot
docker compose exec -T "${REDIS_MASTER:-redis-1}" redis-cli INFO replication | grep connected_slaves                   # connected_slaves:1
```

**One-time migration** from the single-node stack: the old `pg-data` volume would never run the replication init, and the old `postgres`/`redis` containers keep host ports 5432/6379.

- Already pulled this version: `docker compose down -v --remove-orphans`, then `docker volume rm keyloop-scheduler_pg-data keyloop-scheduler_redis-data` (the old volumes are no longer in the compose file).
- Still on the old checkout: `docker compose down -v` there, then pull.

Then `docker compose up -d --build --wait --remove-orphans` and re-seed (`seeding/seed.sh`).

### Run one app on its own

```bash
docker compose up -d --build server-api nginx
docker compose up -d --build scheduler-batch   # waits for server-api: it owns the migrations
```

### Run from the IDE / Gradle against the Docker infra

```bash
docker compose up -d --wait postgres-1 postgres-2 redis-1 redis-2
./gradlew :server-api:bootRun
./gradlew :scheduler-batch:bootRun
```

### Run / debug one app on the host (scripts)

Each script starts the data nodes (`postgres-1/2`, `redis-1/2`) in Docker, points the app at all of them (`DB_HOSTS`, `REDIS_NODES` with host ports), builds the module's jar and runs it on the host JDK 25.
`--debug` opens a JDWP port for IntelliJ; `--suspend` makes the JVM wait for the debugger.

| Script | App port | Debug port | Note |
|---|---|---|---|
| `scripts/run-server-api.sh` | 8090 (8080 is nginx) | 5005 | runs Flyway; not behind nginx, call `localhost:8090` |
| `scripts/run-scheduler-batch.sh` | 8091 (8081 is the batch container) | 5006 | needs the schema (server-api once); `docker compose stop scheduler-batch` so ShedLock gives the jobs to your instance |

```bash
scripts/run-server-api.sh --debug
scripts/run-scheduler-batch.sh --debug --suspend
```

Other options: `--no-build`, `--no-infra`; env `LOCAL_PORT`, `DEBUG_PORT`, `JAVA_OPTS`.

**IntelliJ:** the configurations in `.run/` appear in the Run/Debug list (folder *keyloop*) after opening the project:
`server-api (run|debug)`, `scheduler-batch (run|debug)` (Shell Script) and `Attach server-api 5005`, `Attach scheduler-batch 5006` (Remote JVM Debug).
To debug: **Run** `server-api (debug)` → wait for `debug: attach IntelliJ to localhost:5005` → **Debug** `Attach server-api 5005` → breakpoints work. Stop the shell config to stop the app (graceful shutdown).

### Several batch replicas

Replicas cannot share host port 8081, so use the override that unpublishes it:

```bash
docker compose -f docker-compose.yml -f docker-compose.scale.yml up -d --scale scheduler-batch=2
```

### Seed data

Test data with edge cases, UUIDv7 ids ([Spec03](planing/Spec03.md)). Needs the schema, so start `server-api` first.

```bash
seeding/seed.sh
```

### Manual API testing (Postman)

`GET /api/v1/availability?dealershipId=&serviceTypeId=&vehicleTypeId=&from=&to=` returns free slots per local date
(`from`/`to` are ISO dates in the dealership's time zone, inclusive, at most 14 days).
[`postman/`](postman/README.md) has a collection + environment with the seed ids and test scripts:

```bash
docker compose up -d --build --wait
seeding/seed.sh
npx newman run postman/keyloop-scheduler.postman_collection.json -e postman/local.postman_environment.json
```

Or import both files into Postman and run the collection with the *keyloop local* environment.

### Load tests (Locust)

[Spec06](planing/Spec06.md). Input requests in `loadtest/input/`, expected outputs in `loadtest/expected/` (one file each per suite, joined by case id). Reports in `loadtest/reports/`.

```bash
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml run --rm --no-deps --entrypoint python locust tools/check_scenarios.py
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml run --rm locust          # smoke
LOADTEST_PROFILE=load LOCUST_USERS=50 LOCUST_RUN_TIME=10m docker compose -f docker-compose.yml -f docker-compose.loadtest.yml run --rm locust
docker compose exec -T "${PG_PRIMARY:-postgres-1}" psql -U scheduler -d scheduler < loadtest/verify/invariants.sql   # 0 rows expected
```

### Build and test

```bash
./gradlew build
```

### Reset all data

```bash
docker compose down -v   # removes pg-data-1 and pg-data-2; both nodes bootstrap again on the next up
```
