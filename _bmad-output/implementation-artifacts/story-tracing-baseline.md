# Story — Jaeger tracing baseline for server-api and scheduler-batch

Status: ready
Owner: Winston (Architect) · Date: 2026-11-01
Spine: [`ARCHITECTURE-SPINE.md`](../planning-artifacts/architecture/architecture-keyloop-tracing-2026-11-01/ARCHITECTURE-SPINE.md) (AD-1…AD-10)
Sources: DLD §12, ROADMAP:154, Spec01:52, `story-outbox-tracing.md` Task 1 (moved here), `deferred-work.md` BK-01, CLAUDE.md §1–§4
Facts checked on `feat/outbox-relay-properties-validation`: Spring Boot 4.1.2, no tracing dependency, no Jaeger in compose, log pattern has `[correlationId]` only, ProblemDetail has no `traceId`, and ArchitectureTest ARC-08 does not know `tracing/`.
Unblocks: `story-outbox-tracing.md` Tasks 2–3.

---

## 1. User story

**As a** developer or operator of the scheduler,
**I want** every API request and every real batch job to show up as a trace in Jaeger, with the same `traceId` in the logs and in the error body,
**so that** I can see where the time goes (HTTP → PostgreSQL → Redis), and jump from a failed response or a log line to the full trace.

Why: today we have logs only, with a `correlationId`. A slow booking or a 409 under load cannot be broken down into its DB and Redis calls without reading logs by hand.

---

## 2. The picture

```
client ──► nginx (clears traceparent) ──► server-api ──OTLP/HTTP──┐
                                           ├─ http POST /api/v1/…  │
                                           ├─ jdbc.query ×N        ├──► jaeger :4318 ──► UI :16686
                                           └─ lettuce GET/EVALSHA  │
scheduler-batch ──OTLP/HTTP────────────────────────────────────────┘
  heartbeat      → tasks.scheduled.execution → spring.batch.job → step → jdbc/lettuce
  relay, empty   → nothing exported (AD-3)
  /actuator/*    → nothing exported (AD-3)

log:   2026-11-02 09:00:01.123 INFO [req-7f3…] [4bf92f35…,00f067aa…] [http-nio-1] …
error: 409 { "title": "Booking failed", …, "traceId": "4bf92f3577b34da6a3ce929d0e0e4736" }
```

---

## 3. Tasks

### Task 1 — Jaeger in docker-compose, nginx

- Add a `jaeger` service: `cr.jaegertracing.io/jaegertracing/jaeger:2.22.0`. Its built-in all-in-one config already caps memory at 100 000 traces, so no config file is needed (AD-5).
- Add a new network `obs-net`. Only `jaeger`, `server-api` and `scheduler-batch` join it.
- Ports: `${JAEGER_UI_PORT:-16686}:16686` and `${JAEGER_OTLP_PORT:-4318}:4318`.
- No healthcheck and no `depends_on`: tracing must never block start-up (AD-2).
- Add to `x-app-env`: `TRACING_OTLP_ENDPOINT: http://jaeger:4318/v1/traces` and `TRACING_SAMPLING_PROBABILITY: ${TRACING_SAMPLING_PROBABILITY:-1.0}` (AD-8, one value for both apps).
- `.env.example`: add `JAEGER_UI_PORT`, `JAEGER_OTLP_PORT` and `TRACING_SAMPLING_PROBABILITY`.
- `infra/nginx/conf.d/proxy.inc`: add `proxy_set_header traceparent "";` and `proxy_set_header tracestate "";` (AD-8).

### Task 2 — Tracing in both apps

- Add these to `gradle/libs.versions.toml`: `datasource-micrometer = "2.3.0"`, and the library `datasource-micrometer-spring-boot`.
- `server-api` and `scheduler-batch` dependencies:
  - `implementation("org.springframework.boot:spring-boot-starter-opentelemetry")`
  - `implementation(libs.datasource.micrometer.spring.boot)`
  - `testImplementation("io.opentelemetry:opentelemetry-sdk-testing")`
- `application.yml` (both apps). Values come only from env vars (CLAUDE.md §3):

```yaml
management:
  tracing:
    sampling:
      probability: ${TRACING_SAMPLING_PROBABILITY:1.0}   # Boot default is 0.1; prod set at deploy
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${TRACING_OTLP_ENDPOINT:http://localhost:4318/v1/traces}
jdbc:
  includes: QUERY      # one span per statement, no connection / result-set spans
```

- Redis spans come from Boot's `LettuceObservationAutoConfiguration` (observation name `lettuce`). There is nothing to write for them.
- `service.name` defaults to `spring.application.name` (AD-8).

### Task 3 — Noise filters in both apps (AD-3)

`config/tracing/` in each app, with names in `constant/TracingConstants`:

| Class | Type | Drops |
|---|---|---|
| `NoiseObservationPredicate` | `ObservationPredicate` | `http.server.requests` whose path starts with the actuator base path (both apps); `tasks.scheduled.execution` whose `ScheduledTaskObservationContext` target class is `OutboxRelay` (batch only, matched with `ClassUtils.getUserClass`) |
| `RootSpanExportingPredicate` | `SpanExportingPredicate` | a **root** span (no parent span id) whose name starts with `jdbc.` or `lettuce` |

- Why two filters: Lettuce sets its parent on the Netty event loop, *after* an `ObservationPredicate` has run. A "no parent" check at that point would drop every Redis span. At export time the parent is known.
- Javadoc Part 1 states the numbers (500 ms tick ≈ 170k traces a day; health checks ≈ 8.6k a day). Part 2 is a kept/dropped table.

### Task 4 — `traceId` in logs and errors

- `common-logging/log4j2-spring.xml` → `… %5p [%X{correlationId}] [%X{traceId},%X{spanId}] [%t] …` (AD-6).
- `common-logging`: add `TraceAttributes` (final class, private constructor). It is empty of outbox names for now; the outbox story fills it (AD-7).
- `ErrorMessages`: add `TRACE_ID_PROPERTY = "traceId"`.
- server-api `web/TraceIds`: a small component that wraps Micrometer `Tracer` and returns `Optional<String>` with the current trace id.
- `ApiExceptionHandler`: one private step sets `traceId` on **every** ProblemDetail, including the inherited `ResponseEntityExceptionHandler` paths (override `handleExceptionInternal`). If there is no span, it skips the property (AD-4). Update the class Javadoc example.
- `deferred-work.md`: close BK-01.

### Task 5 — ArchitectureTest (both apps, AD-10)

- ARC-08: add `BASE + ".tracing.."`.
- ARC-05: add `"..tracing.*"` (interfaces only).
- New ARC-09: classes from `io.micrometer.tracing..` and `io.micrometer.observation..` are used only in `..tracing..impl..`, `..config..` and (server-api) `..web.TraceIds`.
- New ARC-10: only `..service..impl..` may depend on `..tracing..` (outside `impl`).
- Update the class Javadoc to say "ARC-01…10".

### Task 6 — Tests and docs

Every tracing IT: `@AutoConfigureTracing` (otherwise `@SpringBootTest` turns export off and the test passes for the wrong reason), plus a test `@Bean InMemorySpanExporter`. Wait for the batch export delay with Awaitility, not a sleep.

| Test | Covers |
|---|---|
| server-api IT: `GET /api/v1/availability` → one trace: HTTP root, `jdbc.query` child, `lettuce` child | AC1 |
| server-api IT: exporter endpoint pointed at a closed port; request still `200` | AC2 |
| server-api IT: captured log line has the exported trace id | AC3 |
| server-api IT: 400 (validation), 404 and 409 bodies have `traceId` equal to the exported trace id; with sampling `0.0` the body still has `traceId` | AC4 |
| batch IT: relay on, no due rows, `/actuator/health` called, 3 s → 0 spans | AC5 |
| batch IT: one heartbeat run → one trace whose `jdbc.query` / `lettuce` spans have a parent | AC6 |
| `ArchitectureTest` ARC-09 / ARC-10 green, and red on a planted bad class (checked by hand once) | AC7 |
| Load test BK-01 passes | AC4 |

- README: add a "Tracing" section with the Jaeger URL, how to find a trace by `traceId`, the env vars, and the smoke check (open a booking trace; stop Jaeger and see that bookings still work).
- CLAUDE.md layout: add `config/tracing/` and `tracing/` (both apps), `TraceAttributes` (common-logging), and ARC-09/10.

---

## 4. Acceptance criteria

- **AC1** Given the stack is up, when a client calls `GET /api/v1/availability` through nginx, then Jaeger shows one `server-api` trace. It has an HTTP root span with `jdbc.query` and `lettuce` child spans.
- **AC2** Given Jaeger is stopped, when requests run, then they succeed, p95 stays within 5 ms of the baseline, and the app logs no stack trace for each request.
- **AC3** Given a request, then every log line it writes shows the same `traceId` as its trace, and still shows its `correlationId`.
- **AC4** Given any error response from server-api (400, 404, 409 or 422), then the ProblemDetail has `traceId`, 32 lowercase hex characters, equal to the request's trace id. This holds even when the trace is not sampled.
- **AC5** Given the relay is enabled, no outbox rows are due, and health checks run, when 1 minute passes, then no root `jdbc.*`, `lettuce`, relay `tasks.scheduled.execution` or actuator span is exported by either app.
- **AC6** Given the heartbeat job runs, then Jaeger shows one `scheduler-batch` trace. Its root is the scheduled-task span, with the Batch job and step spans and their JDBC and Redis children.
- **AC7** Given the code, then ArchitectureTest passes with ARC-09 and ARC-10, and `tracing/` is an accepted package.

---

## 5. Files

```
gradle/libs.versions.toml                                   + datasource-micrometer 2.3.0
docker-compose.yml                                          + jaeger 2.22.0, obs-net, TRACING_* in x-app-env
.env.example                                                + JAEGER_*, TRACING_SAMPLING_PROBABILITY
infra/nginx/conf.d/proxy.inc                                + clear traceparent / tracestate
server-api/build.gradle.kts, scheduler-batch/build.gradle.kts  + starter-opentelemetry, datasource-micrometer, sdk-testing
*/src/main/resources/application.yml                        + management.tracing / opentelemetry, jdbc.includes
common-logging/src/main/resources/log4j2-spring.xml         + [%X{traceId},%X{spanId}]
common-logging/.../logging/TraceAttributes.java             (new, empty holder)
server-api/.../api/constant/ErrorMessages.java              + TRACE_ID_PROPERTY
server-api/.../api/web/TraceIds.java                        (new)
server-api/.../api/web/ApiExceptionHandler.java             + traceId on every ProblemDetail
*/.../constant/TracingConstants.java                        (new, both apps)
*/.../config/tracing/NoiseObservationPredicate.java         (new, both apps)
*/.../config/tracing/RootSpanExportingPredicate.java        (new, both apps)
*/src/test/.../ArchitectureTest.java                        ARC-05, ARC-08 widened; ARC-09, ARC-10 new
README.md, CLAUDE.md                                        + tracing section / layout
_bmad-output/implementation-artifacts/deferred-work.md      BK-01 closed
```

---

## 6. Out of scope

- Business spans (`outbox.write`, `outbox.claim`, `outbox.work`), the `trace_parent` column and span links. These are in `story-outbox-tracing.md`, under spine AD-1, AD-7 and AD-9.
- Metrics, OTel Collector, Prometheus/Grafana, nginx spans, OTLP log export and persistent Jaeger storage (spine § Deferred).

---

## 7. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Load tests at sampling `1.0` fill Jaeger's memory. | The 100k cap evicts old traces. Set `TRACING_SAMPLING_PROBABILITY=0.1` in `docker-compose.loadtest.yml` if the UI gets slow. |
| R2 | The predicate matches the wrong names after a Boot upgrade. | AC5 and AC6 tests break first. The names live in `TracingConstants` only. |
| R3 | The exporter's WARN rate while Jaeger is down was not measured. | Check it during the AC2 test. If it is noisy, lower the level of the exporter logger in `log4j2-spring.xml`. |

---

## 8. Definition of done

- AC1–AC7 pass, the Task 6 tests are green, and BK-01 is green in the load test.
- The README smoke check is written.
- `story-outbox-tracing.md` points its Task 1 here, and its Tasks 2–3 follow spine AD-7 and AD-9 (names `RelayTracing` / `OutboxWriteTracing`, `TraceAttributes`, `Propagator` only).
