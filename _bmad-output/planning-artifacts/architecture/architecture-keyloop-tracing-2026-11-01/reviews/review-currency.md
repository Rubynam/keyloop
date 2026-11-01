---
review: currency / reality-check
target: ARCHITECTURE-SPINE.md (AD-1…AD-8) + story-tracing-baseline.md
date: 2026-11-01
lens: Was each committed decision web-researched or checked against the project, or only asserted from training data?
verdict: PASS WITH FIXES (1 high, 2 medium, 3 low)
---

# Currency review — keyloop tracing spine + story-tracing-baseline

## Method

- **Web:** Spring Boot 4.1.2 reference docs and the properties appendix, Spring Framework 7 observability docs, Maven Central metadata and POMs, the datasource-micrometer README, source and docs, jaegertracing.io docs and download page, Jaeger GitHub releases, `all-in-one.yaml` and `Dockerfile` at tag `v2.20.0`, and Spring Boot source at tag `v4.1.2`.
- **Project / local:** the Gradle cache holds the exact jars the repo resolves. I read their bytecode and sources with `javap` and `unzip`:
  - `spring-boot-dependencies-4.1.2.pom`
  - `spring-boot-data-redis-4.1.2`
  - `spring-boot-micrometer-observation-4.1.2`
  - `spring-boot-batch-4.1.2`
  - `spring-context-7.0.10`
  - `spring-batch-core-6.0.6`
  - `micrometer-observation-1.17.2`
  - `lettuce-core-7.5.2.RELEASE` (sources)

## Summary table

| # | Claim in spine/story | Status | Correct value / note |
|---|---|---|---|
| 1 | `spring-boot-starter-opentelemetry` exists in Boot 4.1 | **Verified** | It is in the Boot 4.1.2 BOM and the 4.1.2 tracing docs |
| 1b | `management.opentelemetry.tracing.export.otlp.endpoint` | **Verified** | Listed in the 4.1.2 properties appendix. It has no default, and transport defaults to `http` |
| 1c | `management.tracing.sampling.probability` | **Verified** | Default is `0.1`. The sampler defaults to `parent-based-trace-id-ratio`, which matches AD-8 |
| 1d | Boot 4.1.2 is current | **Verified** | It is the latest GA. 4.2.0-RC1 exists (2026-10-22) |
| 2 | Boot auto-configures Lettuce tracing | **Verified** | `LettuceObservationAutoConfiguration` in `spring-boot-data-redis-4.1.2` |
| 2b | AD-3: drop a Redis observation when `context.getParentObservation()==null` | **WRONG (high)** | Lettuce sets the parent *after* the predicate runs, so this rule would drop **every** Redis span. See F1 |
| 3 | `@Scheduled` observation name and context | **Verified** | `tasks.scheduled.execution`, `ScheduledTaskObservationContext#getTargetClass()/getMethod()`, auto-configured by `ScheduledTasksObservationAutoConfiguration` |
| 4 | datasource-micrometer 2.3.0 supports Boot 4.1 | **Verified** | It is the latest release (2026-09-13), and its POM is built against Boot **4.1.1** |
| 4b | "result-set spans off → one span per SELECT" | **Under-specified / partly wrong (medium)** | The property is `jdbc.includes` (default `CONNECTION,QUERY,FETCH,KEYS`). Turning off FETCH still leaves 2 spans (connection + query). See F3 |
| 5 | Jaeger 2.20.0 is latest | **Out of date (medium)** | The latest is **2.22.0** (released 2026-10-06; the docs page was updated 2026-10-08). 2.20.0 dates from July. See F2 |
| 5b | Max-traces cap needs a config file | **Partly wrong (low)** | The built-in all-in-one config already sets `max_traces: 100000`. No file is needed. See F2 |
| 6 | Batch job/step observations parent the JDBC spans | **Verified** | Spring Batch 6.0.6 `AbstractJob`/`AbstractStep` call `start()` + `openScope()`. `BatchObservationAutoConfiguration` wires the registry |
| 7 | "In-memory span exporter from the starter's test support" | **WRONG (medium)** | No starter ships an in-memory exporter. See F4 |
| 8 | `service.name` = `spring.application.name` by default | **Verified** | `OpenTelemetryResourceAttributes` Javadoc. Both apps set the name |
| 9 | W3C propagation default | **Verified** | `management.tracing.propagation.produce` default is `[W3C]` |
| 10 | Exporter WARN "about once per minute" | **Unconfirmed (low)** | Not checked. Keep the story wording "check on first run" |
| 11 | Jaeger healthcheck in compose | **Unconfirmed (low)** | The image has no `HEALTHCHECK`, and its base image is set by a build arg. See F5 |

---

## Findings

### F1 — HIGH: the AD-3 "no parent → drop" rule silently kills all Redis spans

**Claim (spine AD-3, story Task 3.2):** an `ObservationPredicate` returns `false` for any JDBC or Redis observation whose `context.getParentObservation()` is `null`.

**Reality check:**
- Micrometer 1.17.2 `Observation.createNotStarted(name, supplier, registry)` calls `context.setParentFromCurrentObservation(registry)` and *then* `isObservationEnabled(name, context)`. The parent is therefore visible to a predicate, but only if the observation is created **on the caller's thread**.
  - JDBC (datasource-micrometer) creates its observations on the caller's thread, so the rule works for JDBC.
- Lettuce 7.5.2 `MicrometerTracing` works differently:
  - `MicrometerSpan.start()` builds the observation from `CommandHandler.write`, which runs on the **Netty event-loop thread**.
  - It sets the real parent later: `createObservation(context).parentObservation(micrometerTraceContext.getObservation())`.
  - So when the predicate runs, there is no current observation and `getParentObservation()` is `null`. This happens for every Redis command, including those inside an HTTP request.
  - The predicate would return `false`, and Redis would vanish from all traces. That breaks **AC1** and **AC6**.
- The Lettuce observation name is `lettuce` (`RedisObservation#getName`). Use that constant, not "redis".

**Fix:**
- Keep the `ObservationPredicate` for (a) the `OutboxRelay` scheduled observation and (b) orphan JDBC only.
- Drop orphan Redis at **export** time with a `SpanExportingPredicate` bean. It should drop a `FinishedSpan` whose `getParentId()` is empty and whose name or tags show Lettuce. Optionally, JDBC can be handled the same way.
- Boot 4.1.2 `OpenTelemetryTracingAutoConfiguration.otelSpanProcessor` injects `ObjectProvider<SpanExportingPredicate>` into the `CompositeSpanExporter`, so a plain bean works.
- Add an IT that checks the Redis child span is present under `GET /api/v1/availability`. This catches the bug.
- Also match the relay with `ClassUtils.getUserClass` / `getTargetClass()`, not `getClass()`. ShedLock proxies `@Scheduled` beans.

**Sources:**
- Lettuce `MicrometerTracing.java` (7.5.2): https://github.com/redis/lettuce/blob/main/src/main/java/io/lettuce/core/tracing/MicrometerTracing.java
- Boot 4.1.2 `OpenTelemetryTracingAutoConfiguration`: https://github.com/spring-projects/spring-boot/blob/v4.1.2/module/spring-boot-micrometer-tracing-opentelemetry/src/main/java/org/springframework/boot/micrometer/tracing/opentelemetry/autoconfigure/OpenTelemetryTracingAutoConfiguration.java
- Lettuce under Spring Data Redis observability (Boot recommends its auto-config): https://docs.spring.io/spring-data/redis/reference/4.0/observability.html
- Local: `javap` on `micrometer-observation-1.17.2.jar` `Observation.createNotStarted`, and `lettuce-core-7.5.2.RELEASE-sources.jar`.

### F2 — MEDIUM: Jaeger 2.20.0 is two minors behind, and the max-traces cap is already built in

**Claim:** "Jaeger v2 latest 2.20.0" (memlog). The story also says "if the default all-in-one config has no cap, mount `infra/jaeger/config.yaml`".

**Reality:**
- Releases: **v2.22.0** (Oct 6) and v2.21.0 (Sep 14) are newer than v2.20.0 (Jul 20).
- 2.21 removes unused v1 HTTP endpoints, and 2.22 validates a positive `max_traces`. Neither breaks all-in-one with memory storage.
- The built-in `all-in-one.yaml` at tag v2.20.0 (and on main) already contains this:
  ```yaml
  jaeger_storage:
    backends:
      some_storage:
        memory:
          max_traces: 100000
  ```
- Jaeger v2 reads no env vars for this setting. To change the cap, use `--set=extensions.jaeger_storage.backends.some_storage.memory.max_traces=N` as a compose `command`, or a `--config` file.
- The image sets `JAEGER_LISTEN_HOST=0.0.0.0`, so OTLP 4318 is reachable from other containers without config.

**Fix:**
- Pin `cr.jaegertracing.io/jaegertracing/jaeger:2.22.0`.
- Drop the conditional `infra/jaeger/config.yaml` from the story and state that the default cap is 100 000 traces.
- Add `--set` only if a different cap is wanted.

**Sources:**
- https://www.jaegertracing.io/download/
- https://github.com/jaegertracing/jaeger/releases
- https://raw.githubusercontent.com/jaegertracing/jaeger/v2.20.0/cmd/jaeger/internal/all-in-one.yaml
- https://www.jaegertracing.io/docs/latest/storage/memory/
- https://www.jaegertracing.io/docs/latest/deployment/configuration/
- https://raw.githubusercontent.com/jaegertracing/jaeger/v2.20.0/cmd/jaeger/Dockerfile

### F3 — MEDIUM: the "result-set spans off" property is not named, and "one span per SELECT" needs QUERY only

**Reality:**
- The property is `jdbc.includes` (`@ConfigurationProperties("jdbc")`), a `Set<TraceType>` that defaults to `CONNECTION, QUERY, FETCH, KEYS`.
- Turning off result-set spans means `jdbc.includes: CONNECTION,QUERY` (and KEYS if wanted). That still gives **2** spans per SELECT, connection + query.
- Only `jdbc.includes: QUERY` gives one. The query span is then a direct child of the HTTP or job span, and the AD-3 orphan check moves from `jdbc.connection` to `jdbc.query`.
- Observation names are `jdbc.connection`, `jdbc.query`, `jdbc.result-set` and `jdbc.generated-keys`. These are the constants for `TracingConstants`.
- Compatibility is verified. 2.3.0 is the latest (released 2026-09-13), and its POM depends on Boot **4.1.1** modules. The README matrix says "2.x = Boot 4.x".

**Fix:** add `jdbc.includes: ${TRACING_JDBC_INCLUDES:QUERY}` (or `CONNECTION,QUERY`) to both `application.yml` files. Name the JDBC observation constants in Task 3.

**Sources:**
- https://repo1.maven.org/maven2/net/ttddyy/observation/datasource-micrometer-spring-boot/maven-metadata.xml
- https://repo1.maven.org/maven2/net/ttddyy/observation/datasource-micrometer-spring-boot/2.3.0/datasource-micrometer-spring-boot-2.3.0.pom
- https://github.com/jdbc-observations/datasource-micrometer (compatibility table)
- https://raw.githubusercontent.com/jdbc-observations/datasource-micrometer/main/datasource-micrometer-spring-boot/src/main/java/net/ttddyy/observation/boot/autoconfigure/JdbcProperties.java
- https://jdbc-observations.github.io/datasource-micrometer/docs/current/docs/html/

### F4 — MEDIUM: there is no in-memory span exporter in the starter's test support, and `@SpringBootTest` disables export

**Claim (spine Conventions, story Task 5):** "Use the in-memory span exporter from the starter's test support."

**Reality:**
- `spring-boot-starter-opentelemetry-test` 4.1.2 pulls in only these:
  - `spring-boot-starter-opentelemetry`
  - `spring-boot-starter-test`
  - `spring-boot-starter-micrometer-metrics-test`
  - `spring-boot-micrometer-tracing-test`, which pulls in `micrometer-observation-test`
- None of these contains an OTel `InMemorySpanExporter`.
- Boot 4.1.2 docs also say: "tracing components which are reporting data are not auto-configured when using `@SpringBootTest`", and tell you to annotate with `@AutoConfigureTracing`.

**Fix:**
1. Add `testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")` and `testImplementation("io.opentelemetry:opentelemetry-sdk-testing")`. The version comes from the OTel BOM 1.62.0, which Boot manages.
2. Expose `InMemorySpanExporter.create()` as a `@Bean SpanExporter` in a test config. Boot collects every `SpanExporter` bean into the `CompositeSpanExporter`.
3. Put `@AutoConfigureTracing` on every tracing IT, or the exporter gets 0 spans and AC5 passes for the wrong reason.
4. Remember that the `BatchSpanProcessor` exports every 5 s (`schedule-delay`). Either await the result, or lower the delay in the test profile.

**Sources:**
- https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-opentelemetry-test/4.1.2/spring-boot-starter-opentelemetry-test-4.1.2.pom
- https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-micrometer-tracing-test/4.1.2/spring-boot-micrometer-tracing-test-4.1.2.pom
- https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html ("Using Tracing")

### F5 — LOW: Jaeger healthcheck command not confirmed

The v2.20.0 `Dockerfile` has no `HEALTHCHECK`, and its base image comes from a build arg, so I could not confirm that `wget` or a shell exists. The health endpoint is `:13133` (`healthcheckv2`, listening on `0.0.0.0` in the image).

**Fix:** before writing the compose `healthcheck`, run `docker run --rm --entrypoint wget cr.jaegertracing.io/jaegertracing/jaeger:2.22.0 --help`. If that fails, leave the healthcheck out, since nothing `depends_on` Jaeger anyway.

### F6 — LOW: exporter log throttling claim not checked

"Exporter failures log a WARN at most about once per minute" was not checked against OTel SDK 1.62.0. The story already frames it as "check on first run", which is acceptable.

---

## Verified detail (for the record)

- **Starter and properties (1).**
  - The Boot 4.1.2 tracing docs say: "Tracing with OpenTelemetry and reporting using OTLP requires … `spring-boot-starter-opentelemetry`".
  - The appendix lists `management.opentelemetry.tracing.export.otlp.endpoint` (no default; transport `http`) and `management.tracing.sampling.probability` (default 0.1).
  - The BOM holds `spring-boot-starter-opentelemetry` and `-test`, OTel 1.62.0, Micrometer 1.17.2, Micrometer Tracing 1.7.2, Spring Framework 7.0.10, Spring Batch 6.0.6 and Lettuce 7.5.2.
  - Boot also maps `OTEL_EXPORTER_OTLP_*` env vars (`management.opentelemetry.map-environment-variables=true`). The `TRACING_*` scheme in the story is fine, but someone setting `OTEL_*` would also be honoured.
  - Sources:
    - https://docs.spring.io/spring-boot/reference/actuator/tracing.html
    - https://docs.spring.io/spring-boot/appendix/application-properties/index.html
    - https://docs.spring.io/spring-boot/reference/actuator/observability.html
    - https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/
    - https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/maven-metadata.xml
- **Lettuce (2).**
  - `LettuceObservationAutoConfiguration` is `@ConditionalOnClass(RedisClient, MicrometerTracing, ObservationRegistry)` and `@ConditionalOnBean(ObservationRegistry)`.
  - It registers a `ClientResourcesBuilderCustomizer` with `new MicrometerTracing(registry, serviceName)`.
  - No manual customizer is needed, so the story's fallback can be removed.
  - Source: local `javap` of `spring-boot-data-redis-4.1.2.jar`, plus https://docs.spring.io/spring-data/redis/reference/4.0/observability.html.
- **`@Scheduled` (3).**
  - The name is `tasks.scheduled.execution`, the convention is `DefaultScheduledTaskObservationConvention`, and the context is `org.springframework.scheduling.support.ScheduledTaskObservationContext`.
  - The context has `getTargetClass()`, `getMethod()` and `isComplete()`. Low-cardinality keys are `code.function` and `code.namespace`.
  - Boot 4.1.2 `ScheduledTasksObservationAutoConfiguration` (in `spring-boot-micrometer-observation`) wires it automatically. `SchedulingConfig` defines no custom `SchedulingConfigurer`, so it applies.
  - Story risk R2 is resolved.
  - Source: https://docs.spring.io/spring-framework/reference/integration/observability.html, plus local `javap` of `spring-context-7.0.10.jar`.
- **Spring Batch (6).**
  - Observation names are `spring.batch.job` and `spring.batch.step`.
  - Both call `Observation.start()` followed by `openScope()`, so JDBC and Redis calls inside a tasklet get the step as parent.
  - Boot's `BatchObservationAutoConfiguration` registers `BatchObservabilityBeanPostProcessor` when an `ObservationRegistry` bean exists.
  - JobRepository metadata writes made **before** the job observation opens are orphans when the job is started from the relay. For heartbeat they sit under the `tasks.scheduled.execution` root, so AC6's root span will be the scheduled span, with the job span as its child. Reword AC6 to match.
  - Source: local `javap` of `spring-batch-core-6.0.6.jar` and `spring-boot-batch-4.1.2.jar`.
- **Identity (8).** The `OpenTelemetryResourceAttributes` Javadoc says `service.name` defaults to `spring.application.name` (else `unknown_service`). `server-api` and `scheduler-batch` both set it.
  - Source: https://docs.spring.io/spring-boot/api/java/org/springframework/boot/opentelemetry/autoconfigure/OpenTelemetryResourceAttributes.html
