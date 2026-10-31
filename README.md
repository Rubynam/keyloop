# keyloop — Unified Service Scheduler

Keyloop technical assessment, Scenario A. Design notes: [ROADMAP.md](ROADMAP.md), [docs/](docs/).

## Modules

| Module | Role | Port |
|---|---|---|
| `nginx` | Load balancer, single entry point; discovers `server-api` replicas via Docker DNS ([Spec02](planing/Spec02.md)) | 8080 |
| `server-api` | REST API, owns the DB schema (Flyway), talks to PostgreSQL + Redis. 2 replicas by default, reached only through nginx | — |
| `loadtest` | Locust load tests, profile `loadtest` only ([Spec04](planing/Spec04.md)) | 8089 (UI) |
| `scheduler-batch` | Spring Batch worker jobs, scheduled with ShedLock (one run per tick across replicas) | 8081 (actuator) |
| `common-logging` | Shared library: Log4j2 config, correlation id, method-logging aspect. Not a runnable app | — |

Stack: Java 25 · Spring Boot 4.1 · Gradle 9 (wrapper) · PostgreSQL 18 · Redis 8.

## Setup

**Requirements**

- Docker with Compose v2.20+ (needs `--wait` and `!reset` in `docker-compose.scale.yml`).
- For IDE / Gradle runs only: any JDK 17+ to start the wrapper; Gradle downloads the Java 25 toolchain itself (foojay resolver).
- For regenerating seed data only: Python 3.9+ (`seeding/generate.py`). Loading the seed needs Docker only.

**Configuration**

```bash
cp .env.example .env
```

| Variable | Default | Used for |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `scheduler` | Database and credentials (shared by Postgres and both apps) |
| `POSTGRES_PORT` / `REDIS_PORT` | `5432` / `6379` | Host ports, for IDE runs and DB clients |
| `API_PORT` | `8080` | nginx (the API entry point) |
| `BATCH_PORT` | `8081` | `scheduler-batch` actuator |
| `HEARTBEAT_CRON` | `*/30 * * * * *` | `heartbeatJob` schedule (Spring cron, with seconds) |
| `TZ` | `Asia/Ho_Chi_Minh` | JVM zone of the app containers: log timestamps and cron |

Every variable has a default in `docker-compose.yml`, so `.env` is only needed to change one. Change ports here if `5432`/`6379`/`8080`/`8081` are already taken on your machine.

## Quick start

```bash
docker compose up -d --build --wait     # postgres, redis, 2× server-api, scheduler-batch, nginx
seeding/seed.sh                         # optional: load test data (schema is already migrated)
```

`--wait` returns once every container is healthy. Startup order is enforced by health checks: postgres/redis → `server-api` (runs Flyway) → `scheduler-batch` and nginx.

Check that it works:

```bash
curl localhost:8080/api/v1/ping
curl localhost:8080/actuator/health
curl localhost:8081/actuator/health
docker compose ps
```

Logs:

```bash
docker compose logs -f scheduler-batch   # heartbeatJob every 30 s
ls logs/                                 # Log4j2 files, one per container (<app>-<container id>.log)
```

Stop with `docker compose down` (keeps data) or `docker compose down -v` (wipes Postgres and Redis).

## Running in other ways

### Load balancer and API replicas

`localhost:8080` is nginx. Every response carries `X-Upstream-Addr` (which replica answered) and `X-Request-Id`.

```bash
for i in $(seq 10); do curl -s -o /dev/null -D - localhost:8080/api/v1/ping | grep -i x-upstream; done
docker compose up -d --scale server-api=3 --no-recreate server-api   # nginx picks it up in ~5 s, no reload
docker compose up -d --scale server-api=2 --no-recreate server-api
```

Networks: `edge` (nginx only, published) · `lb-net` (internal, nginx ↔ server-api) · `data-net` (apps ↔ postgres/redis; nginx is not on it).

### Run one app on its own

```bash
docker compose up -d --build server-api nginx
docker compose up -d --build scheduler-batch   # waits for server-api: it owns the migrations
```

### Run from the IDE / Gradle against the Docker infra

```bash
docker compose up -d postgres redis
./gradlew :server-api:bootRun
./gradlew :scheduler-batch:bootRun
```

The apps default to `localhost` for Postgres/Redis and write log files to `<repo>/logs`. Start `server-api` first: it owns the Flyway migrations.

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

### Load tests (Locust)

[Spec04](planing/Spec04.md). Input requests in `loadtest/input/`, expected outputs in `loadtest/expected/` (one file each per suite, joined by case id). Reports in `loadtest/reports/`.

```bash
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml run --rm --no-deps --entrypoint python locust tools/check_scenarios.py
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml run --rm locust          # smoke
LOADTEST_PROFILE=load LOCUST_USERS=50 LOCUST_RUN_TIME=10m docker compose -f docker-compose.yml -f docker-compose.loadtest.yml run --rm locust
docker compose exec -T postgres psql -U scheduler -d scheduler < loadtest/verify/invariants.sql   # 0 rows expected
```

### Build and test

```bash
./gradlew build
```

### Reset all data

```bash
docker compose down -v
```
