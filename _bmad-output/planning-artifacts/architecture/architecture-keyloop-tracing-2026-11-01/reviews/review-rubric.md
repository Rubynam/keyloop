---
review-of: ARCHITECTURE-SPINE.md (keyloop distributed tracing, 2026-11-01)
reviewer: independent rubric review
date: 2026-11-01
inputs: ARCHITECTURE-SPINE.md, story-tracing-baseline.md, story-outbox-tracing.md, docker-compose*.yml, .env.example,
  server-api + scheduler-batch application.yml and Dockerfiles, ApiExceptionHandler, RequestIdFilter,
  common-logging log4j2-spring.xml, both ArchitectureTest classes, RelayConstants, OutboxRelay, infra/nginx/conf.d, CLAUDE.md
---

# Review — tracing architecture spine

## Verdict

**Revise before story-outbox-tracing starts.** The spine is a good fit for story-tracing-baseline: the stack choice,
export, log ids, error `traceId` and the Jaeger placement all match the codebase. But it misses the one real
cross-app contract (the `trace_parent` column). AD-1 and AD-3 conflict in a way that would silently drop the child
spans the outbox story needs. The `tracing/` package it creates would fail `ArchitectureTest` ARC-08. Its noise rule
also ignores the Docker health checks that already run every 10 s, so baseline AC5 fails under compose.

## Checklist

| # | Criterion | Result | Notes |
|---|---|---|---|
| 1 | Fixes the real divergence points for the two stories, misses none | **Partial** | Missing: outbox `trace_parent` contract (F3), the server-api side of `tracing/` (F3), how `tracing` fits into ArchitectureTest (F2), health-check noise (F4) |
| 2 | Every AD rule is enforceable and prevents its divergence | **Partial** | AD-1 + AD-3 conflict (F1). AD-7 names clash with existing code (F5). AD-2/4/5/6/8 are enforceable through ACs |
| 3 | Nothing under Deferred lets two units diverge | **Partial** | Sampling deferral lets the two apps sample apart and leaves links dangling. The nginx pass-through of `traceparent` is a live decision, not a deferral (F6) |
| 4 | Ratifies, does not contradict, the codebase | **Mostly** | Compose networks, `x-app-env`, `RequestIdFilter`, `ApiExceptionHandler` (via `handleExceptionInternal`) and the log4j2 pattern are respected. Conflicts: ARC-08 package list (F2), `RelayConstants` vs `<Feature>Constants` (F5), `ErrorMessages.ERRORS_PROPERTY` precedent (F5), Dockerfile HEALTHCHECK (F4) |
| 5 | Operational envelope decided or deferred | **Partial** | Local compose is decided. Production deployment, Jaeger memory limit and UI exposure are not mentioned (F8) |
| 6 | Story consistent with spine | **Mostly** | Baseline story matches AD-1…AD-8. Gaps: `TRACING_SAMPLING_PROBABILITY` never reaches the containers (F6); test support is not named (F7). The outbox story uses names that differ from the spine (F5) and has stale AC1.x references (F8) |

## Findings

### F1 — HIGH — AD-1 lets custom spans use raw `Tracer`, and AD-3 then drops their JDBC/Redis children

- AD-1 allows custom spans through "Micrometer `Tracer` / `ObservationRegistry`". AD-3 drops "every JDBC or Redis
  observation that has no parent **observation**" (`context.getParentObservation() == null`).
- The relay's `@Scheduled` observation is dropped on purpose, so `outbox.work` is a root. If `MicrometerRelayTracing`
  opens `outbox.work` with `Tracer.nextSpan()` (the natural API when you need a span **link**, outbox story Task 3),
  no Observation is current. The Redis `EVALSHA` and JDBC `UPDATE` inside it then have no parent observation, and the
  predicate drops them. The outbox story's picture (`outbox.work` → redis ×N, jdbc UPDATE) cannot appear, and nothing
  fails loudly. The same applies to `outbox.write` in server-api, if its JDBC child must survive any predicate added
  there later.
- **Fix:** pick one rule in the spine. Either (a) AD-3: the predicate (in `config/`, where `Tracer` is allowed) drops a
  JDBC/Redis observation only when `getParentObservation() == null` **and** `tracer.currentSpan() == null`; or (b) AD-1:
  every business span must be an `Observation` opened in scope, and the spine names how the link is attached to it.
  (a) is simpler and does not depend on how the link is built. Add an outbox-story AC that `outbox.work` has its
  Redis/JDBC children.

### F2 — HIGH — The `tracing/` package is unknown to ArchitectureTest; the outbox story will fail the build

- ARC-08 (`knownLayerPackagesOnly`) in both apps lists `controller, service, repository, cache, store, model, dto,
  exception, constant, config, web, schedule, job`. There is no `tracing`. `tracing/outbox/RelayTracing` fails ARC-08
  as soon as it is added.
- Nothing enforces the spine's own claims. "`tracing/<feature>/` holds the interface" is not in ARC-05
  (`portsAreInterfaces` covers `service.*`, `repository.*`, `cache.*`, `store.*` only). "Services call the port" is not
  in ARC-01: `tracing` is in no layer, so a controller, schedule or repository can call it freely.
- Baseline Task 5 only adds a "`Tracer` used only in web/config/tracing impl" rule. CLAUDE.md is updated but the tests
  are not.
- **Fix:** add a rule to the spine (AD-1 or a new AD), with the matching edits in baseline Task 5:
  add `BASE + ".tracing.."` to ARC-08 in both apps; add `"..tracing.*"` to ARC-05; in ARC-01, define a `Tracing` layer
  (`BASE + ".tracing.."`) with `mayOnlyBeAccessedByLayers("Service")`. Then the outbox story inherits a green build.

### F3 — HIGH — No AD for the one contract that crosses apps: `outbox_events.trace_parent`

- server-api writes the column (outbox story Task 2). scheduler-batch parses it (Task 3). Seed SQL and
  `loadtest/simulator` write rows straight to PostgreSQL with `NULL`. This is the only place where the two apps must
  agree byte for byte, and the spine is silent about it.
- Open points that two implementers would settle differently: whose context is stored (request span or
  `outbox.write`); whether the string comes from the configured `Propagator` or is hand-formatted; the regex and the
  sampled-flag meaning; link vs parent (decided only in story prose); behaviour on a bad value.
- The server-api side is not seeded either. The Structural Seed puts `tracing/` only under `scheduler-batch`. Where
  the `outbox.write` port lives in server-api (`tracing/appointment/`? `tracing/outbox/`?) and which constants class
  holds its name are undecided. The outbox writer is `repository/appointment/impl/JdbcOutboxRepository`, so the
  feature is `appointment` while the span is `outbox.write`. AD-7's `<feature>.<verb>` does not say whether "feature"
  means the package or the domain concept.
- **Fix:** add AD-9 "Outbox trace context". (1) The column holds the W3C `traceparent` of the `outbox.write` span,
  injected and extracted only with Micrometer `Propagator`, never with string formatting. (2) `NULL` and unparseable
  values mean "no link", with no error. (3) The relay always starts a new root and attaches a link, never a parent.
  (4) server-api has `tracing/appointment/` + `impl/`, and the service passes the `traceparent` to the repository as a
  model field. (5) In AD-7, "feature" means the domain prefix (`outbox`) for the span name; the code still sits in the
  package of the feature that owns it.

### F4 — MEDIUM — Health checks create the trace noise AD-3 means to stop, and baseline AC5 fails in compose

- Both Dockerfiles run `HEALTHCHECK … /actuator/health/readiness` every 10 s. Readiness includes `db` and `redis`
  (both `application.yml`). Each probe is an HTTP-rooted trace with JDBC and Redis children, so the AD-3 predicate
  keeps it. That is about 8 600 traces per day per container (2 api replicas + 1 batch), and more under
  `--scale`.
- Baseline AC5 ("relay enabled, no due rows, 1 minute → **no span from scheduler-batch**") is false in the compose
  stack: about 6 health traces per minute. The IT may pass only because it has no health checker.
- **Fix:** extend AD-3 to both apps. An `ObservationPredicate` in `config/tracing/` also drops `http.server.requests`
  whose `ServerRequestObservationContext` path starts with the actuator base path, which is kept in
  `TracingConstants`. Reword AC5 to "no relay or orphan span", or keep its wording and add the actuator drop.

### F5 — MEDIUM — Naming conventions contradict existing code and the downstream story

- Spine: `<Feature>Tracing` / `Micrometer<Feature>Tracing`, constants in `constant/<feature>/<Feature>Constants`.
  For the batch `outbox` feature that gives `OutboxTracing` and `OutboxConstants`.
- Code and story: the existing class is `constant/outbox/RelayConstants`. The outbox story names `RelayTracing` /
  `MicrometerRelayTracing` and puts span names in `RelayConstants`. So the spine and its consumer already disagree.
- server-api ProblemDetail property names already live in `ErrorMessages` (`ERRORS_PROPERTY`). Baseline Task 4 adds
  `TracingConstants.TRACE_ID_PROPERTY`, which starts a second home for the same kind of constant.
- **Fix:** ratify the code. "Span/tag names go in the feature's existing constants class (batch outbox:
  `RelayConstants`). The port is named after the sub-feature that emits the span (`RelayTracing`)." Put
  `TRACE_ID_PROPERTY` in `ErrorMessages` next to `ERRORS_PROPERTY`. `TracingConstants` holds only observation names
  and prefixes.

### F6 — MEDIUM — Sampling and inbound `traceparent` are left open in a way that lets units diverge

- Deferred "production sampling ratio" is a per-app env var. Nothing says that both apps use the same value. The relay
  trace is an independent root (outbox T1), so with ratio *p* only about *p²* of links resolve, and with different
  values per app the result is unpredictable. The T1 idea of a custom sampler that follows the linked flag is a code
  change, not a deploy-time value.
- The baseline story adds `TRACING_SAMPLING_PROBABILITY` to `.env.example` but **not** to `x-app-env`, so it never
  reaches the containers. Risk R1's load-test mitigation also has no effect: `docker-compose.loadtest.yml` does not
  override `server-api`.
- "nginx passes `traceparent` through unchanged" is listed as Deferred, but it is a live trust decision. With
  parent-based sampling (AD-8), any public client chooses the trace id that ends up in our logs and error bodies, and
  can force `sampled=01` at any production ratio. `proxy.inc` forwards every client header today.
- **Fix:** AD-8: one `TRACING_SAMPLING_PROBABILITY` in `x-app-env` for both apps, and relay roots follow the
  sampled flag of the linked context (or accept dangling links, in writing). AD-5 or AD-8: nginx clears the inbound
  `traceparent` and `tracestate` (`proxy_set_header traceparent "";`) until nginx spans exist.

### F7 — LOW — The test convention is not specific enough to stop two different test setups

- "In-memory span exporter from the starter's test support": Boot does not ship one under that name. Boot test
  contexts also turn off tracing export by default unless the test opts in (`@AutoConfigureTracing` in Boot 4, the
  successor of `@AutoConfigureObservability`; check the exact name on 4.1.2). Without that, AC1/AC4/AC5 ITs see zero
  spans, or an AC5 "0 spans" test passes for the wrong reason.
- **Fix:** name it: `io.opentelemetry:opentelemetry-sdk-testing` `InMemorySpanExporter` as a `@TestConfiguration`
  bean plus the Boot tracing auto-config annotation, shared by both stories. Every "0 spans" test also needs a
  positive control in the same context.

### F8 — LOW — Smaller gaps

- **AC6 job span.** Spring Batch job/step observations need an `ObservationRegistry` wired into Batch. If Boot 4.1
  does not do that, the heartbeat trace root is `tasks.scheduled.execution` (from `JobScheduler`), not a job span.
  Add this to R2 as "verify first".
- **AD-4 and sampling.** An unsampled request still has a valid trace id, so the error body carries an id that Jaeger
  never received. State it, or omit `traceId` when `!sampled`. BK-01 only needs the field to exist at 1.0.
- **AD-4 coverage.** Errors handled outside MVC (Boot `/error`, filter failures) bypass `ApiExceptionHandler`.
  "Every API error" should say "every error resolved by MVC".
- **Ops envelope.** No production target is described. The Jaeger UI (no auth) and OTLP are published on all host
  interfaces. There is no container memory limit next to the max-traces cap. `obs-net` must be non-internal because of
  the published ports. Say so, like the `data-net` comment does. The Jaeger v2 image may have no `wget`/`curl`. Base the
  healthcheck on the `healthcheckv2` extension (:13133), or drop it, since the apps do not `depends_on` Jaeger.
- **AD-1 wording.** AD-1 says the tracer API is used only in `tracing/**/impl`. Baseline Task 5 / AC7 also allow
  `web/` (`TraceIds`) and `config/` (the predicate). Align the AD text with the test.
- **Outbox story is stale.** It still cites AC1.1–AC1.3 (Task 4, DoD), which moved to the baseline. Its "facts
  checked" branch is `refactor/spec05-pr2-pr5`. "Tracing the cleanup Batch job" is listed as out of scope while AD-3
  says the cleanup job keeps its spans. Clarify: no *custom* spans.
- **Deferred async propagation.** This is correct today: there is no `@Async`, executor or `CompletableFuture` in
  either app's main code. Keep it.

## What is right and should stay

- AD-1's single stack (Boot starter + datasource-micrometer, no agent) fits Boot 4.1.2 and avoids double context.
- AD-5's network placement matches the existing `edge` / `lb-net` / `data-net` design: nginx and Locust stay off it,
  and the apps do not `depends_on` Jaeger.
- AD-6 keeps `correlationId` from `RequestIdFilter` next to `traceId`. The filter runs at `HIGHEST_PRECEDENCE`, before
  the HTTP observation filter, so both ids are present on every request log line.
- AD-4's hook through `handleExceptionInternal` / `createResponseEntity` covers the inherited
  `ResponseEntityExceptionHandler` paths that `ApiExceptionHandler` already overrides.
- Keeping span creation at "claimed rows only" matches `OutboxRelay`'s 500 ms `fixedDelay` loop.
