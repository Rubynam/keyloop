---
title: 'Jaeger tracing baseline for server-api and scheduler-batch'
type: 'feature'
created: '2026-11-01'
status: 'done'
route: 'dispatch'
baseline_commit: '786b25ae62bf2e9a5b69f8dcadfea354ef212116'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/story-tracing-baseline.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-keyloop-tracing-2026-11-01/ARCHITECTURE-SPINE.md'
  - '{project-root}/CLAUDE.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The apps have logs with a `correlationId` only. A slow booking or a 409 under load cannot be split into its HTTP, PostgreSQL and Redis parts, and error bodies carry no id to find the trace (load-test BK-01 fails).

**Approach:** Implement `story-tracing-baseline.md` Tasks 1–6 as written, under spine AD-1…AD-10: Boot OTel starter + datasource-micrometer in both apps, OTLP/HTTP to an in-memory Jaeger 2.22.0 on `obs-net`, two noise filters per app, `traceId` in the log pattern and on every ProblemDetail, ArchUnit ARC-09/10.

## Boundaries & Constraints

**Always:** Spine AD-1…AD-10 are binding. CLAUDE.md §1–§4 (layering, constructor injection, no literals, two-part Javadoc). Observation names live only in each app's `constant/TracingConstants`. Endpoint and sampling come only from `TRACING_OTLP_ENDPOINT` / `TRACING_SAMPLING_PROBABILITY`. Every tracing IT uses `@AutoConfigureTracing` + a test `@Bean InMemorySpanExporter`, and waits with Awaitility.

**Never:** OTel Java agent, community OTel starter, raw OTel SDK, `Tracer.spanBuilder`. Business outbox spans, `trace_parent` column, links (`story-outbox-tracing.md`). Collector, metrics, nginx spans, persistent Jaeger. `depends_on` / healthcheck on Jaeger. A test that calls Jaeger.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Read path | `GET /api/v1/availability` | one trace: HTTP root, `jdbc.query` + `lettuce` children | N/A |
| Error body | 400 / 404 / 409 / 422 | ProblemDetail `traceId` = 32 lowercase hex = exported trace id | no current span → property omitted |
| Unsampled | sampling `0.0`, any error | body still has `traceId`; nothing exported | N/A |
| Exporter down | endpoint on a closed port | request `200`, no per-request stack trace | async batch exporter drops |
| Batch noise | relay on, no due rows, `/actuator/health` | 0 spans exported | N/A |
| Heartbeat | one run | one trace; `jdbc.query` / `lettuce` spans have a parent | N/A |

</frozen-after-approval>

## Code Map

- `gradle/libs.versions.toml` -- has `[versions]`/`[libraries]`; add `datasource-micrometer = "2.3.0"` + `datasource-micrometer-spring-boot` (`net.ttddyy.observation`).
- `server-api/build.gradle.kts`, `scheduler-batch/build.gradle.kts` -- add starter-opentelemetry, datasource-micrometer, `opentelemetry-sdk-testing` (test). `spring-boot-starter-test` (Awaitility) already comes from root `build.gradle.kts`.
- `*/src/main/resources/application.yml` -- both have a `management:` block (health/info exposure); merge `tracing` / `opentelemetry` keys there, add top-level `jdbc.includes`.
- `docker-compose.yml` -- `x-app-env` anchor, networks `edge`/`lb-net`/`data-net`; batch is on `data-net` only, server-api on `lb-net`+`data-net`.
- `.env.example`, `infra/nginx/conf.d/proxy.inc` -- simple key lists; proxy.inc already sets `X-Request-Id`.
- `common-logging/src/main/resources/log4j2-spring.xml` -- `PATTERN` property, one line.
- `common-logging/.../logging/` -- `CorrelationId`, `ClientError` show the constant-holder style (Lombok `@NoArgsConstructor(access = PRIVATE)` is used in apps).
- `server-api/.../web/ApiExceptionHandler.java` -- extends `ResponseEntityExceptionHandler`; `@ExceptionHandler` methods return `ProblemDetail` directly (do not pass through `handleExceptionInternal`), overrides call `handleExceptionInternal`. Both paths need the traceId step. Static helpers `problem(...)`/`invalidRequest(...)`.
- `server-api/.../constant/ErrorMessages.java` -- add `TRACE_ID_PROPERTY` next to `ERRORS_PROPERTY`.
- `*/src/test/.../ArchitectureTest.java` -- ARC-01…08; ARC-05 list `"..service.*", "..repository.*", "..cache.*", "..store.*"`; ARC-08 list of `BASE + ".x.."`.
- `*/src/test/.../support/IntegrationTest.java`, `TestcontainersConfig.java` -- base for ITs; batch base sets Flyway props. `server-api/.../support/LogCapture` captures "LEVEL message" only (extend or capture MDC for AC3).
- `scheduler-batch/.../schedule/OutboxRelay.java` (`@Scheduled fixedDelay`), `JobScheduler.java` (`@Scheduled cron` + ShedLock, `runHeartbeat()`).
- `loadtest/expected/30-booking.json:29` -- BK-01 asserts `traceId` exists on 409.
- `_bmad-output/implementation-artifacts/deferred-work.md:46-48` -- BK-01 entry to close.
- `README.md`, `CLAUDE.md` -- add Tracing section / layout lines.

## Tasks & Acceptance

**Execution:**
- [x] `docker-compose.yml`, `.env.example`, `infra/nginx/conf.d/proxy.inc` -- story Task 1 -- AD-2, AD-5, AD-8
- [x] `gradle/libs.versions.toml`, both `build.gradle.kts`, both `application.yml` -- story Task 2 -- AD-1, AD-2
- [x] `*/constant/TracingConstants.java`, `*/config/tracing/NoiseObservationPredicate.java`, `*/config/tracing/RootSpanExportingPredicate.java` -- story Task 3 -- AD-3
- [x] `log4j2-spring.xml`, `TraceAttributes.java`, `ErrorMessages.java`, `web/TraceIds.java`, `ApiExceptionHandler.java`, `deferred-work.md` -- story Task 4 -- AD-4, AD-6, AD-7
- [ ] both `ArchitectureTest.java` -- story Task 5 -- AD-10
- [ ] server-api `TracingIT` (AC1–AC4), batch `TracingIT` (AC5, AC6), `README.md`, `CLAUDE.md` -- story Task 6

**Acceptance Criteria:** story `story-tracing-baseline.md` §4 AC1–AC7 apply as written. In-build proof: AC1, AC3, AC4, AC5, AC6, AC7 by tests; AC2 by the closed-port IT (functional part only).

## Implementation Notes

Implemented directly in the orchestrating session (no implementation subagent): the investigation context was already loaded.

- **Span names differ from the story/spine.** datasource-micrometer names a statement span `query` (observation `jdbc.query`); Lettuce names its span after the command (`get`, `mget`, `evalsha`). `RootSpanExportingPredicate` therefore matches by tag: any `jdbc.*` tag, or `db.system=redis`. Names in `TracingConstants` (`JDBC_TAG_PREFIX`, `DB_SYSTEM_TAG`, `DB_SYSTEM_REDIS`). Root = `SpanId.isValid(parentId)` false.
- **Batch relay match by class name.** `config` referencing `schedule.OutboxRelay` breaks ARC-01 (`consideringAllDependencies`), so `TracingConstants.OUTBOX_RELAY_CLASS` holds the FQN; batch TracingIT AC5 fails on a rename.
- **`@AutoConfigureTracing` lives in `spring-boot-starter-opentelemetry-test`** (added as testImplementation in both apps, next to `opentelemetry-sdk-testing`).
- **OTLP metrics and log export turned off** in both `application.yml` (`management.otlp.metrics.export.enabled`, `management.logging.export.otlp.enabled`): the starter brings `micrometer-registry-otlp` with export on by default, out of scope (tracing only, spine Deferred).
- **ArchUnit:** ARC-05 / ARC-10 use `BASE + ".tracing.*"` / `BASE + ".tracing.."`, not `"..tracing.*"`, which would also match `config.tracing`. ARC-09 exempts `web.TraceIds` by FQN and `cache.availability.impl.RedisAvailabilityCache` (pre-existing fail-open span event, Spec05 PR2) — logged in `deferred-work.md`. Planted-class check done by hand: ARC-09 and ARC-10 red, `tracing/` interface accepted.
- **`@WebMvcTest` slices** have no `Tracer`: `support/WebSliceTracingConfig` (Tracer.NOOP + TraceIds) imported by the four slice tests.
- **Tests:** `BatchSpanProcessor` waits for each OTLP batch, and the closed-port exporter retries, so in-memory export lags. server-api TracingIT waits up to 15 s; batch TracingIT turns OTLP off and has a positive control. AC5 tests in both apps were mutation-checked (predicates disabled → red).
- **Not verified in build:** AC1/AC2 against real Jaeger via compose, AC2 p95 budget, BK-01 load-test rerun, exporter WARN rate (R3).

## Spec Change Log

## Review Triage Log

Pass 1 (blind-hunter BH, edge-case-hunter EC, verification-gap VG). No intent_gap / bad_spec → no loopback.

| # | Finding | Verdict | Evidence / route |
|---|---|---|---|
| BH1 | README says only *empty* relay ticks untraced; every relay run is dropped | low | True (AD-3 drops all relay ticks until outbox story). patch: README wording |
| BH2 | Test/metrics/logs property names may be wrong | false | All names exist in Boot 4.1.2 jar metadata (checked; VG confirmed) |
| BH3 | UnsampledTracingIT `isEmpty` has no control | low | At ratio 0.0 the sampler never records, so nothing can be exported later; rejected (control adds complexity) |
| BH4 / EC9 | Filter / Tomcat-level errors bypass ApiExceptionHandler, no traceId | low | No filter throws today (RequestIdFilter never does); AD-4 scopes it to ApiExceptionHandler; rejected |
| BH5 | baggage / b3 not stripped; direct callers can force sampling | false | B3 and baggage fields not configured; server-api has no host port (nginx only, Spec02 API-10) |
| BH6 | yml sampling fallback 1.0 | false | Story Task 2 specifies `${TRACING_SAMPLING_PROBABILITY:1.0}`, prod sets it at deploy (AD-8) |
| BH7 | Jaeger ports on all interfaces | low | Same as postgres/redis dev ports; story fixes these mappings; rejected |
| BH8 | Predicates duplicated in both apps | false | Spine AD-3 / Structural Seed place them per app in `config/tracing` |
| BH9 / EC1 / EC2 / VG-o2 | Actuator match: empty base path drops all, `/actuatorx`, context path | low | True for `base-path: /`. patch: strip context path, exact or `base + "/"`, empty guard (both apps) |
| BH10 | No unit tests for predicates | low | Covered by ITs, mutation-checked (predicates off → red); rejected |
| BH11 | New @WebMvcTest must import WebSliceTracingConfig | low | Fails loudly at context start naming `TraceIds`; rejected |
| BH12 | ARC-09 ignores `io.opentelemetry..`; exemption not in CLAUDE.md | low | Spine names Micrometer types only; exemption tracked in deferred-work; rejected |
| BH13 | MdcLogCapture only on root config; Javadoc overclaims | low | Only Root logger in log4j2-spring.xml (false today). patch: Javadoc wording |
| BH14 | `[,]` printed outside spans | low | Spine AD-6 fixes the exact pattern; rejected |
| BH15 | `flush` ignores join timeout | false | Callers retry with Awaitility on the returned list by design |
| BH16 | BK-01 closed without load-test rerun | medium | True: DoD wants BK-01 green in load test. defer: rerun entry in deferred-work |
| BH17 | `TraceAttributes` empty | false | Story Task 4 / AD-7 require it now |
| EC3 | traceId all zeros on invalid context | false | `OtelTracer.currentSpan()` returns null for `Span.getInvalid()` (bytecode checked) |
| EC4 | Bad sampling value fails start-up | false | Loud fail at boot on bad config is correct |
| EC5 / EC6 | Batch AC6 can pick a trace before its root is exported | medium | True (root ends last). patch: await root + step, pick trace that has the root |
| EC7 | Missing traceId → NPE not assertion | low | patch: assert node not null with body |
| EC8 | `Map.copyOf` NPE on null MDC value | low | patch: `new HashMap<>(...)` (direct correction) |
| EC10 | AC1 names `jdbc.query` / `lettuce` not real span names | low | Real names are `query` / Redis command; documented in Implementation Notes + README; rejected |
| VG1 | traceId on 503 / 500 / InvalidRange / inherited 400 never asserted | medium | patch: WebSliceTracingConfig stub Tracer with fixed id + `$.traceId` asserts; mutation (503 without withTraceId) → red |
| VG2 | server-api AC5 has no orphan JDBC span to catch | low | No server-api code path makes one (health uses `isValid`). defer per VG |
| VG3 | Rendered log line not asserted | low | AC3 pins the MDC keys; rejected |
| VG-o1 | nginx header clear untested | low | No nginx harness; rejected |
| R3 (own) | Exporter while Jaeger down | low | Measured: one ERROR + stack trace per failed batch (~8 s), not per request; AC2 holds. Left as is, reported |

## Design Notes

`traceId` on every ProblemDetail needs two hooks: override `handleExceptionInternal` (inherited Spring 400s and our overrides) and wrap the direct `ProblemDetail` returns of `@ExceptionHandler` methods. One private `withTraceId(ProblemDetail)` serves both. `TraceIds` reads `Tracer.currentSpan()`; an unsampled span still has a context, so AC4 holds at sampling `0.0`.

`RootSpanExportingPredicate` checks `SpanData.getParentSpanContext().isValid()`; this is the only safe place, because Lettuce sets its parent later on the Netty event loop.

## Verification

**Commands:**
- `./gradlew build` -- expected: BUILD SUCCESSFUL, ArchitectureTest + both TracingIT green (needs Docker for Testcontainers).

**Manual checks:**
- `docker compose up -d --build`, call availability through nginx, open `http://localhost:16686` → one `server-api` trace (AC1). Stop `jaeger`, bookings still work (AC2). Not runnable inside the build.
