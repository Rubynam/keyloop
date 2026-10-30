# Spec01 — Project Scaffolding: Docker Compose + Gradle Multi-Project

> Keyloop Technical Assessment · Scenario A: The Unified Service Scheduler
> Roadmap: **Phase 2 — Scaffolding**
> Status: 📝 Draft · Owner: Dai Nam

---

## 1. Goal

Set up the runnable skeleton for the whole system, so that later specs only add business logic. When Spec01 is done:

1. One `docker compose up` brings up **PostgreSQL** and **Redis** (latest stable versions).
2. A **Gradle multi-project build** contains **two independent Spring Boot applications**:
   - **PR01 — `scheduler-api`**: handles every REST request/response and talks to PostgreSQL and Redis.
   - **PR02 — `scheduler-batch`**: runs background worker jobs with **Spring Batch**.
3. Each application has **its own Dockerfile**, builds its **own image**, and can be **started, stopped and scaled separately**.

No business features (booking, availability, outbox relay logic) are in scope here — only the wiring that proves every piece connects.

---

## 2. Context — why two applications?

From the [Detail-Level Design](../docs/detail-level-design.md) and decision **D1** in the [Roadmap](../ROADMAP.md):

| Concern | Lives in | Reason |
|---|---|---|
| REST API (availability, booking, cancel, lookups), rate limiting, cache-aside reads | `scheduler-api` | Latency-sensitive, request-driven, scaled by traffic. |
| Outbox relay (`outbox_events` → `INCR` Redis version key), outbox cleanup, future periodic jobs | `scheduler-batch` | Time-/poll-driven, must not steal threads or memory from request handling; scaled and restarted independently. |

Splitting them means a crash, a long-running job or a deploy of the worker never affects the API, and vice versa. They share **only the database and Redis**, never in-process state.

> ℹ️ This replaces the earlier DLD idea of "same image, worker profile" (DLD §13). The DLD deployment diagram should be updated after this spec is approved.

---

## 3. Scope

### In scope
- `docker-compose.yml` with PostgreSQL, Redis, `scheduler-api`, `scheduler-batch`.
- Gradle wrapper, root build, two subprojects, shared build conventions.
- One Dockerfile per subproject (multi-stage, non-root).
- DB schema bootstrap: `btree_gist` extension + Flyway baseline migration.
- Health checks (Docker + Spring Actuator) proving DB and Redis connectivity.
- One trivial **sample Spring Batch job** proving the batch wiring end-to-end.
- `README` section: how to build and run.

### Out of scope (later specs)
- Domain tables (`appointments`, `technicians`, …), booking / availability logic.
- Real outbox relay and cleanup jobs (only their empty placeholders).
- Tracing with Jaeger — DLD §12. (Metrics / Prometheus / Grafana deferred to a later phase.)
- CI pipeline, Kubernetes manifests.

---

## 4. Requirements

### 4.1 Infrastructure (docker-compose)

| ID | Requirement |
|---|---|
| INF-01 | Compose file at repo root: `docker-compose.yml`. |
| INF-02 | **PostgreSQL**: official image, **latest stable major, pinned by major tag** (e.g. `postgres:18-alpine` — confirm the current major on Docker Hub at implementation time). **Do not use the floating `latest` tag** → builds stay reproducible. |
| INF-03 | **Redis**: official image, latest stable major, pinned the same way (e.g. `redis:8-alpine`). |
| INF-04 | PostgreSQL must have the **`btree_gist`** extension enabled (needed for the exclusion constraints in D1). Done by an init script mounted into `/docker-entrypoint-initdb.d/`. |
| INF-05 | Named volumes for data: `pg-data`, `redis-data` → data survives `docker compose down` (removed only with `down -v`). |
| INF-06 | Health checks: Postgres `pg_isready`, Redis `redis-cli ping`. |
| INF-07 | Credentials and ports come from a **`.env`** file; commit only **`.env.example`**, add `.env` to `.gitignore`. |
| INF-08 | Host ports: Postgres `5432`, Redis `6379`, API `8080`, Batch actuator `8081` (all overridable via `.env`). |
| INF-09 | Redis runs with AOF disabled is acceptable (it is a cache only — D1); set `maxmemory` + `allkeys-lru` policy. |
| INF-10 | Infra must be startable **alone** so the apps can run from the IDE: `docker compose up -d postgres redis`. |

### 4.2 Gradle build

| ID | Requirement |
|---|---|
| GR-01 | Gradle **Wrapper** committed (`gradlew`, `gradle/wrapper/*`) — nobody needs a local Gradle install. Use the latest stable Gradle. |
| GR-02 | **Kotlin DSL** (`settings.gradle.kts`, `build.gradle.kts`). |
| GR-03 | Root project name: `keyloop-scheduler`. Subprojects: `scheduler-api`, `scheduler-batch`. |
| GR-04 | **Java toolchain 25** (current LTS, matches local JDK). |
| GR-05 | **Spring Boot**: latest stable (4.x line) — version declared **once** in `gradle/libs.versions.toml` (version catalog). |
| GR-06 | Shared config (Java version, test setup, encoding, group/version) in the root build or a convention plugin — **no copy-paste** between subprojects. |
| GR-07 | Root project itself is **not** a Spring Boot app and produces no jar. |
| GR-08 | Each subproject builds an executable jar independently: `./gradlew :scheduler-api:bootJar`, `./gradlew :scheduler-batch:bootJar`. |
| GR-09 | `./gradlew build` builds and tests both. |
| GR-10 | Base package: `com.keyloop.scheduler.api` and `com.keyloop.scheduler.batch`. |

> **Shared code:** the two apps do **not** depend on each other. If they later need shared types (e.g. outbox event payloads), add a third plain-library module `scheduler-common` in a later spec — not now.

### 4.3 PR01 — `scheduler-api`

| ID | Requirement |
|---|---|
| API-01 | Dependencies: `spring-boot-starter-web`, `-validation`, `-data-jpa`, `-data-redis`, `-actuator`, PostgreSQL driver, **Flyway** (`flyway-core` + `flyway-database-postgresql`). |
| API-02 | **API owns the DB schema**: Flyway migrations live in `scheduler-api/src/main/resources/db/migration` and run on startup. |
| API-03 | Baseline migration `V1__baseline.sql`: `CREATE EXTENSION IF NOT EXISTS btree_gist;` + `outbox_events` table (columns from D1) so the batch app has something to connect to. |
| API-04 | `spring.jpa.hibernate.ddl-auto=validate` — Hibernate never changes the schema. |
| API-05 | Redis via Lettuce, connection settings from env vars. |
| API-06 | Actuator exposes `health` (with `db` and `redis` components) and `info`. Liveness/readiness probes enabled. |
| API-07 | One smoke endpoint `GET /api/v1/ping` → `200 {"status":"ok"}`. |
| API-08 | Errors returned as **RFC 7807 `ProblemDetail`** (`spring.mvc.problemdetails.enabled=true`). |
| API-09 | Runs on port `8080`. |

### 4.4 PR02 — `scheduler-batch`

| ID | Requirement |
|---|---|
| BAT-01 | Dependencies: `spring-boot-starter-batch`, `-jdbc`, `-data-redis`, `-actuator`, PostgreSQL driver. **No `spring-boot-starter-web` controllers** — only the actuator endpoint (port `8081`). |
| BAT-02 | Spring Batch metadata tables (`BATCH_JOB_INSTANCE`, …) live in a **separate schema `batch`** to keep them away from domain tables; created by `spring.batch.jdbc.initialize-schema=always` + `spring.batch.jdbc.table-prefix=batch.BATCH_`. |
| BAT-03 | Batch app **never runs Flyway** on the domain schema — it only reads/writes tables owned by the API. |
| BAT-04 | Jobs must **not auto-run on startup** (`spring.batch.job.enabled=false`). They are launched by a scheduler (`@Scheduled` + `JobOperator`/`JobLauncher`) with a unique `JobParameter` (timestamp) per run. |
| BAT-05 | Sample job `heartbeatJob`: one tasklet step that runs `SELECT 1` on Postgres and `PING` on Redis, then logs the result. Scheduled every 30 s (configurable). Proves DB + Redis + Batch wiring. |
| BAT-06 | Placeholders (empty classes + TODO, disabled by config) for future jobs: `outboxRelayJob`, `outboxCleanupJob`. |
| BAT-07 | **Multi-instance safe**: when several batch containers run, a scheduled job must not run twice at the same time. Use **ShedLock** (JDBC lock provider) — or document the chosen alternative. |
| BAT-08 | Actuator `health` includes `db` and `redis`. |

> ⚠️ **Trade-off to remember:** Spring Batch is designed for chunked, restartable jobs. The future outbox relay is a **high-frequency poll** (D1: `FOR UPDATE SKIP LOCKED` + `LISTEN/NOTIFY`); wrapping each poll in a Batch job creates a metadata row per run. Decide in the outbox spec whether the relay is a Batch job or a plain scheduled worker inside the same app. Cleanup (daily) fits Batch naturally.

### 4.5 Dockerfiles

| ID | Requirement |
|---|---|
| DK-01 | One Dockerfile per app: `scheduler-api/Dockerfile`, `scheduler-batch/Dockerfile`. |
| DK-02 | **Multi-stage**: stage 1 uses a JDK image + Gradle wrapper to build only that module (`./gradlew :<module>:bootJar`); stage 2 uses a **JRE-only** image (e.g. `eclipse-temurin:25-jre-alpine`). |
| DK-03 | Build context = **repo root** (needs the wrapper and root build files); compose sets `context: .` + `dockerfile: <module>/Dockerfile`. |
| DK-04 | Use Spring Boot **layered jar** extraction so dependency layers are cached between builds. |
| DK-05 | Run as a **non-root** user. |
| DK-06 | JVM tuned for containers: `-XX:MaxRAMPercentage=75`. All config via env vars — **no secrets baked into the image**. |
| DK-07 | `HEALTHCHECK` / compose healthcheck hits `/actuator/health`. |
| DK-08 | `.dockerignore` excludes `.git`, `.idea`, `build/`, `.gradle/`, `docs/`, `*.pdf`. |

### 4.6 Running separately

| ID | Requirement |
|---|---|
| RUN-01 | Each app is a separate compose service with its own image tag: `keyloop/scheduler-api`, `keyloop/scheduler-batch`. |
| RUN-02 | `scheduler-api` `depends_on` postgres + redis (`condition: service_healthy`). |
| RUN-03 | `scheduler-batch` `depends_on` postgres + redis (`service_healthy`) **and** `scheduler-api` (`service_healthy`) — because migrations are owned by the API. |
| RUN-04 | Each app can be built, started, restarted and scaled alone, e.g. `docker compose up -d --build scheduler-batch`, `docker compose up -d --scale scheduler-batch=2`. |
| RUN-05 | Each app can also run outside Docker against the compose infra: `./gradlew :scheduler-api:bootRun`. Defaults in `application.yml` point to `localhost`. |

---

## 5. Target Structure

```
keyloop/
├── docker-compose.yml
├── .env.example
├── .dockerignore
├── infra/
│   └── postgres/init/01-extensions.sql     # CREATE EXTENSION btree_gist; CREATE SCHEMA batch;
├── settings.gradle.kts
├── build.gradle.kts                        # shared conventions, no Boot plugin applied
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── gradlew / gradlew.bat
├── scheduler-api/                          # PR01
│   ├── Dockerfile
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/com/keyloop/scheduler/api/
│       │   ├── SchedulerApiApplication.java
│       │   └── web/PingController.java
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/V1__baseline.sql
│       └── test/java/...                   # context-load test
└── scheduler-batch/                        # PR02
    ├── Dockerfile
    ├── build.gradle.kts
    └── src/
        ├── main/java/com/keyloop/scheduler/batch/
        │   ├── SchedulerBatchApplication.java
        │   ├── job/HeartbeatJobConfig.java
        │   ├── job/OutboxRelayJobConfig.java     # placeholder
        │   ├── job/OutboxCleanupJobConfig.java   # placeholder
        │   └── schedule/JobScheduler.java
        ├── main/resources/application.yml
        └── test/java/...
```

---

## 6. Configuration (environment variables)

| Variable | Used by | Default (local) |
|---|---|---|
| `POSTGRES_DB` | postgres, api, batch | `scheduler` |
| `POSTGRES_USER` | postgres, api, batch | `scheduler` |
| `POSTGRES_PASSWORD` | postgres, api, batch | `scheduler` (local only) |
| `DB_HOST` / `DB_PORT` | api, batch | `localhost` / `5432` (`postgres` inside compose) |
| `REDIS_HOST` / `REDIS_PORT` | api, batch | `localhost` / `6379` (`redis` inside compose) |
| `API_PORT` | api | `8080` |
| `BATCH_PORT` | batch | `8081` |
| `HEARTBEAT_CRON` | batch | `*/30 * * * * *` |

---

## 7. Implementation Steps

1. **Infra** — `docker-compose.yml` (postgres + redis only), `.env.example`, init SQL. Verify with `docker compose up -d postgres redis`.
2. **Gradle skeleton** — wrapper, `settings.gradle.kts`, root `build.gradle.kts`, version catalog. Verify `./gradlew projects`.
3. **PR01 `scheduler-api`** — Boot app, Flyway baseline, ping endpoint, actuator, context-load test. Verify `bootRun` against compose infra.
4. **PR02 `scheduler-batch`** — Boot app, Batch config in `batch` schema, heartbeat job + scheduler, ShedLock, placeholders. Verify heartbeat logs.
5. **Dockerfiles** — one per module + `.dockerignore`.
6. **Compose the apps** — add both services with health checks and `depends_on`.
7. **Docs** — README "Getting started"; update DLD §13 deployment diagram and ROADMAP Phase 2.

---

## 8. Acceptance Criteria

- [ ] `docker compose up -d --build` → all 4 services reach **healthy**.
- [ ] `docker compose exec postgres psql -U scheduler -c '\dx'` lists `btree_gist`.
- [ ] `curl localhost:8080/api/v1/ping` → `200 {"status":"ok"}`.
- [ ] `curl localhost:8080/actuator/health` → `UP` with `db` and `redis` components `UP`.
- [ ] `curl localhost:8081/actuator/health` → `UP` with `db` and `redis` `UP`.
- [ ] `docker compose logs scheduler-batch` shows `heartbeatJob` completing every ~30 s; rows appear in `batch.batch_job_execution`.
- [ ] `flyway_schema_history` contains `V1__baseline`; `outbox_events` exists.
- [ ] `docker compose stop scheduler-batch` → API keeps serving; `docker compose stop scheduler-api` → running batch keeps working.
- [ ] `docker compose up -d --scale scheduler-batch=2` → heartbeat still runs **once** per tick (ShedLock).
- [ ] `./gradlew build` passes; `./gradlew :scheduler-api:bootJar` and `:scheduler-batch:bootJar` each produce one jar.
- [ ] Neither image runs as root (`docker compose exec scheduler-api whoami` ≠ `root`).
- [ ] No secrets committed; only `.env.example` in git.

---

## 9. Open Questions

- [ ] Outbox relay: Spring Batch job vs plain scheduled worker in `scheduler-batch` (see trade-off in §4.4).
- [ ] Need a `scheduler-common` library module later for shared event payloads?
- [ ] Batch metadata in the same database (separate schema) vs a dedicated database — same DB chosen for now for simplicity.
- [ ] Testcontainers for integration tests — introduce here or in Phase 4 (Testing)?
