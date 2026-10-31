# keyloop — Unified Service Scheduler

Keyloop technical assessment, Scenario A. Design notes: [ROADMAP.md](ROADMAP.md), [docs/](docs/).

## Modules

| Module | Role | Port |
|---|---|---|
| `nginx` | Load balancer, single entry point; discovers `server-api` replicas via Docker DNS ([Spec02](planing/Spec02.md)) | 8080 |
| `server-api` | REST API, owns the DB schema (Flyway), talks to PostgreSQL + Redis. 2 replicas by default, reached only through nginx | — |
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
docker compose logs -f scheduler-batch   # heartbeatJob every 30 s
```

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

### Build and test

```bash
./gradlew build
```

### Reset all data

```bash
docker compose down -v
```
