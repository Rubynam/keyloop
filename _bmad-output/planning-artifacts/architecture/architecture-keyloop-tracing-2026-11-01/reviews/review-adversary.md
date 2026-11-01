---
review: adversary
target: ARCHITECTURE-SPINE.md (keyloop distributed tracing, 2026-11-01)
units: story-tracing-baseline.md, story-outbox-tracing.md
lens: two units that obey every AD to the letter and still build incompatibly
date: 2026-11-01
verdict: NOT BUILD-SAFE — 5 holes must be closed (3 blocking, 2 high) before story-outbox-tracing starts
---

# Adversarial review — tracing spine

## Verdict

**Not build-safe yet.** The spine is sound on the stack (AD-1), export (AD-2), Jaeger (AD-5) and logs (AD-6).
But its two interfaces that *cross* units — "how a business span is made" and "what goes into `trace_parent`" — are
left open. I can build the baseline and the outbox story so that each passes every AD word for word, and the result
still fails AC3.1, AC3.3 or `ArchitectureTest`. The holes are below, ordered by damage.

Code checked on `feat/outbox-relay-properties-validation`: `OutboxRelay`, `OutboxRelayServiceImpl`,
`OutboxEventRepository`, `RelayConstants`, `OutboxEvent`, `ApiExceptionHandler`, `RequestIdFilter`,
`JdbcOutboxRepository`, `AppointmentServiceImpl#insertWithEvent`, both `ArchitectureTest` classes, `CorrelationId`.

| # | Severity | Hole | Units that clash |
|---|---|---|---|
| H1 | Blocking | AD-3 predicate drops the JDBC/Redis children of `outbox.work` / `outbox.claim` | baseline T3 × outbox T3 |
| H2 | Blocking | `trace_parent`: no owner of its format, no layer path from writer to port | outbox T2 (server-api) × outbox T3 (batch) × AD-1 |
| H3 | Blocking | `tracing/` is not a known package; tracer rule has two owners | baseline T5 × outbox T3/DoD × ARC-05/08 |
| H4 | High | Two names for one port and one constants owner; `outbox.write` has no feature home | spine conventions × outbox T3 × server-api layout |
| H5 | High | Sampling vs links vs error `traceId`: three units decide sampling three ways | AD-8 × AD-4 × outbox T1 risk |
| H6 | Medium | Span scope vs transaction scope is not fixed | outbox T3 × `OutboxRelayServiceImpl` TX A/B |
| H7 | Medium | `relay.lag.ms` source and `OutboxEvent` shape conflict | outbox T3 rule × outbox Files list |
| H8 | Low | Client-controlled sampling through nginx | AD-8 × Deferred "nginx passes traceparent" |

---

## H1 — The AD-3 predicate silently drops the relay's own child spans (Blocking)

**Unit A — baseline Task 3, by the letter of AD-3.** `RelayNoiseObservationPredicate` returns `false` for "every
JDBC or Redis observation that has no parent observation", checked with `context.getParentObservation() == null`
(the story says exactly this).

**Unit B — outbox Task 3, by the letter of AD-1.** AD-1 allows custom spans through "Micrometer `Tracer` /
`ObservationRegistry`". The developer picks `Tracer.spanBuilder()` for `outbox.work`, because that is the obvious
Micrometer API that takes a link (`addLink`) — which the story requires.

**Clash.** A span started with `Tracer` and put in scope with `tracer.withSpan(...)` is *not* an Observation.
The JDBC and Redis observations inside it see no parent *observation*, so the predicate drops all of them. Jaeger shows
`outbox.work` with no `redis EVALSHA` and no `jdbc UPDATE` children — the picture in outbox story §2 never appears.
AC3.1 still passes (it only checks the link and the outcome), so nothing catches it.

Second trap on the same rule: whether `getParentObservation()` is already filled when a predicate runs depends on
how the instrumentation creates the observation (Micrometer evaluates predicates in `createNotStarted`, before
`start()`). The baseline's AC5 test would pass even if the field were *always* null — it then drops every JDBC span,
including heartbeat's, and AC6 is the only guard. The story's R2 hints at this for the scheduled name, not for the
parent test.

Third trap: `outbox.claim` "starts at each `claimNext()` that returns a row". It can only know a row came back *after*
`lockNextDue()` ran. So `lockNextDue` SQL is always parentless and dropped. That is the right outcome for empty ticks,
but it must be written down, or one developer opens `outbox.claim` before the SQL (span on every empty tick → AC3.3 fails)
and another after it (no claim SELECT ever visible).

**AD fix — tighten AD-1 and AD-3:**

- **AD-1 (add):** "A custom span is always an **Observation** created through `ObservationRegistry`, so it is a parent
  for framework observations and passes through `ObservationPredicate`. `Tracer` is used only to *read* context
  (current trace id, `traceparent` inject/extract) and to attach links. If the Observation API cannot carry a link at
  start, the story's spike documents the one allowed mechanism; no other path."
- **AD-3 (reword the rule as behaviour, not as a field):** "A JDBC or Redis observation is dropped when **there is no
  current observation in the registry** at the time it is created (`registry.getCurrentObservation() == null`). The
  predicate test asserts both sides: an empty tick exports 0 spans, and a heartbeat run exports JDBC children."
- **AD-3 (add):** "`outbox.claim` opens *after* `lockNextDue()` returns a row and wraps `countTry` / `markFailed` only.
  The claim SELECT is never traced."

---

## H2 — `trace_parent` has a reader and a writer but no owner (Blocking)

**Writer (server-api).** Outbox Task 2: "the outbox writer opens a short span `outbox.write` and stores its W3C
`traceparent`". The writer is `JdbcOutboxRepository.append(...)`. AD-1 forbids the tracer outside `tracing/**/impl`, and
the spine says "services call the port". So the repository may not make the string, and the service must. Two legal builds:

| Build | Who makes the string | `OutboxRepository.append` |
|---|---|---|
| A | `AppointmentServiceImpl` calls `OutboxTracing.write(() -> outbox.append(..., tracing.currentTraceParent()))` | grows a 5th `String traceParent` arg |
| B | port impl formats `"00-%s-%s-%s".formatted(ctx.traceId(), ctx.spanId(), ctx.sampled() ? "01" : "00")` and a new field is added to `AppointmentBookedPayload` | unchanged; the id goes into the JSON payload |

Build B obeys every AD (no column rule exists in the spine), puts the context in `payload`, and the batch reader looks
in the column → never links. Build A with a hand-made format writes `-null` when `TraceContext.sampled()` is `null`
(Micrometer allows that), which the batch reader rejects as "bad", so AC3.4 hides it as "no link".

**Reader (batch).** "A bad `trace_parent` string is ignored." The reader may parse with a regex, or with the
`Propagator.extract`. These accept different inputs (version `ff`, upper-case hex, `tracestate`). A writer that
injects `tracestate` too has nowhere to put it.

**AD fix — new AD-9 "Outbox trace context":**

- The column `outbox_events.trace_parent` is the only carrier (never the payload). It holds exactly the W3C `traceparent`
  header value, nothing else; `tracestate` is not kept.
- It is produced only by `Propagator.inject` and read only by `Propagator.extract`, both inside `tracing/**/impl`.
  No code formats or parses the string by hand.
- It crosses the service layer as an opaque `String` (or a `model/` value type), returned by the server-api port and
  passed to `OutboxRepository.append`. Repositories never call the port.
- `NULL` when there is no current span **or** when the current span is not sampled (see H5).
- The migration (`V5`) is owned by server-api; the batch IT fixtures (`support/OutboxRows`) and the load-test simulator
  / seeding (`loadtest/simulator/simulate.py`, `seeding/generate.py`) keep writing `NULL`. Say so in the AD, because
  they are three more writers of `outbox_events`.

---

## H3 — `ArchitectureTest` rejects the package the spine creates; the tracer rule has two owners (Blocking)

**Fact:** ARC-08 in *both* apps lists the allowed top-level packages
(`controller, service, repository, cache, store, model, dto, exception, constant, config, web, schedule, job`).
`tracing` is not in it. ARC-05 ("feature package of a port layer holds interfaces only") matches
`..service.*, ..repository.*, ..cache.*, ..store.*` — not `..tracing.*`.

**Unit A — baseline Task 5** adds "a tracer-usage rule" (`Tracer` / `ObservationRegistry` only in `web/`, `config/`,
`tracing/**/impl`) and says `tracing/` stays empty. ARC-08 stays as is: still green, because there is no class yet.

**Unit B — outbox story** adds `tracing/outbox/RelayTracing` and its impl, and its DoD says "`ArchitectureTest` green,
including the new `tracing/` port rule" — a *second* rule author. Its Files list does not touch `ArchitectureTest`.

**Clash.** The first class in `tracing/` fails ARC-08 in batch. In server-api, the `outbox.write` port has nowhere to
live at all: the spine's Structural Seed gives server-api only `web/TraceIds` and `constant/TracingConstants`.
Also, because `tracing` is not an ArchUnit layer in ARC-01, a controller or a `Jdbc*Repository` may call a tracing port
and every rule stays green. And a concrete class in `tracing/outbox/` passes, since ARC-05 does not cover it.

**AD fix — new AD-10 "Tracing rules in ArchitectureTest" (owned by story-tracing-baseline, both apps):**

- ARC-08 gets `.tracing..` in both apps.
- ARC-05 adds `..tracing.*` (ports are interfaces; classes go in `impl/`).
- New ARC-09: `Tracer`, `ObservationRegistry`, `Propagator` are used only in `..tracing..impl..`, `..config..`, and the
  one `web/` reader (`TraceIds`).
- New ARC-10: `..tracing..` is accessed only by `..service..impl..` (not by controllers, schedules, jobs, repositories).
- The outbox story only adds classes and must not add or change rules. Fix its DoD wording.
- Structural Seed: add `server-api/.../api/tracing/<feature>/` + `impl/`.

---

## H4 — Two names for the port, three homes for the names (High)

| Concern | Spine says | Outbox story says |
|---|---|---|
| Port | `<Feature>Tracing` / `Micrometer<Feature>Tracing`; first feature = `outbox` → `OutboxTracing` | `RelayTracing` / `MicrometerRelayTracing` |
| Span/tag constants | `constant/<feature>/<Feature>Constants` → `constant/outbox/OutboxConstants` | `RelayConstants` (existing class) |
| Predicate names | `constant/TracingConstants` (per app) | — |
| ProblemDetail `traceId` key | baseline: `TracingConstants.TRACE_ID_PROPERTY` | — (but `ErrorMessages.ERRORS_PROPERTY` already owns problem property keys) |

**Clash 1 — span name vs feature.** AD-7: span name `<feature>.<verb>`. In server-api the feature package is
`appointment` (`repository/appointment/OutboxRepository`). A developer obeying AD-7 names the write span
`appointment.write` and puts it in `AppointmentConstants`; the outbox story says `outbox.write`. Both obey AD-7.
Jaeger search by `outbox.*` then misses the producer side.

**Clash 2 — shared tag names across two apps.** `event.id`, `event.type` (and later more) are written in server-api
(`outbox.write`) and in batch (`outbox.work`). Each app's own constants class is "the" owner by AD-7, so the two can drift
(`event.type` vs `outbox.event.type`). There is no shared module except `common-logging`.

**Clash 3 — two `TracingConstants`.** Same simple name, two different contents (server-api: a ProblemDetail key;
batch: observation names). Harmless at compile time, but AD-7 / Consistency say nothing about which one a *span name*
may go in, so a third developer puts `outbox.work` in `TracingConstants` because "it is tracing".

**AD fix — tighten AD-7 + Consistency Conventions:**

- "The *feature* in a span name is the **business concept the span is about**, not the package. Outbox spans are
  `outbox.*` in both apps."
- "Span and tag names shared by two apps live in one class in `common-logging` (`com.keyloop.scheduler.logging.TraceNames`,
  or similar). App constants classes reference them; they never redefine them."
- "`constant/TracingConstants` holds only names that the *framework* produces (observation names the predicate matches).
  It never holds a span or tag name we create, and never a response field." Move `traceId` to `ErrorMessages` next to
  `ERRORS_PROPERTY`.
- Pick one port name and write it in the spine: `OutboxTracing` (batch) and `OutboxTracing` (server-api) — or
  `RelayTracing` / `OutboxWriteTracing` — but one rule, and fix the story to match.

---

## H5 — Sampling, links and the error `traceId` are decided in three places (High)

- **AD-8:** parent-based sampling with a root ratio. The relay span is a *new root* (outbox story: link, not parent),
  so it is sampled by ratio, independent of the booking. At prod ratio `p`, a linked pair is kept with probability `p²`.
- **Outbox story T1** proposes "sample `outbox.work` when the linked context has `-01`". That is a custom sampler.
  OTel's stock `ParentBased(TraceIdRatioBased)` ignores links. A developer who builds T1 does not break any AD word
  (AD-8 says "parent-based", which a link-aware sampler arguably is not, but nothing forbids an extra rule), and a
  developer who does not build it does not either. Two compliant builds, different prod behaviour.
- **AD-4:** "every error body carries `traceId`". With `p < 1`, `TraceIds` still returns a trace id for an unsampled
  request (the context exists, it is just not recorded). The client quotes an id that Jaeger never received. The
  baseline's AC4 runs at `1.0`, so it cannot see it. AD-6 has the same issue in logs, which is fine for log search, but
  the error body is a support promise.

**AD fix — tighten AD-8 and AD-4:**

- AD-8: "The sampler is the stock parent-based ratio sampler in both apps. **No link-aware or custom sampler** until
  the Deferred 'production sampling' decision. A relay trace may therefore point at a booking trace that was not kept;
  this is accepted." (Or the reverse decision — but write it in the spine, not in a story risk table.)
- AD-4: "`traceId` is set when there is a current span, **sampled or not**; the README says an id may be missing from
  Jaeger when sampling is below 1.0." Or: "only when sampled" — pick one; the AC must test the unsampled case.
- AD-9 (from H2): write `trace_parent` only for a sampled span, so the relay never links into the void.

---

## H6 — Span scope vs transaction scope (Medium)

`OutboxRelayServiceImpl.work` opens TX B with `transaction.execute(...)`, and `relock` is the first statement.
The connection is taken at TX begin. If the port opens `outbox.work` *inside* the callback (natural: the outcome is
decided there), the `datasource-micrometer` connection observation starts before it, with no current observation →
dropped by AD-3. Whether the query spans then attach to `outbox.work` or vanish with their dropped connection parent
depends on library internals. If the port wraps the whole `transaction.execute`, they attach cleanly. Both builds obey
every AD. Same for `outbox.write` in server-api: `insertWithEvent` runs inside the booking transaction, so this one is
safe only because the HTTP span is always the parent.

**AD fix — add to AD-3 (or AD-9):** "A business span is opened **before** its transaction begins and closed after it
ends; the port API takes the unit of work as a callback (`<T> T work(OutboxEvent row, Supplier<T> body)`), and the
outcome tag is set through the port before return." This also fixes where `relock_skipped` is tagged.

---

## H7 — `relay.lag.ms` and the `OutboxEvent` shape (Medium)

Outbox Task 3: lag = PostgreSQL `now() − created_at` **at the start of the work**, never the JVM clock.
Outbox Files: `OutboxEvent` gains `traceParent, createdAt (for lag)`; `claimNext reads … now() − created_at`.

Build A reads `created_at` into the record and computes `Duration.between(createdAt, Instant.now())` in the port
— uses the JVM clock, breaks the Spec04 §4.2 rule, but the spine does not carry that rule. Build B computes lag in the
claim SQL (TX A), which is *before* the work, not at its start — off by the commit time of TX A and violates the story's
own definition. Build C adds it to the `relock` query (TX B), which today returns `boolean`. Also `claimNext()` rebuilds
the row with the 4-argument constructor, so any new field is silently lost unless the rebuild is changed too.

**AD fix — add to AD-9:** "`relay.lag.ms` is computed in SQL by the query that starts the work (`relock`, TX B)
and returned with it; `OutboxEvent` carries `traceParent` only, not `createdAt`." Or state TX A as the defined moment
and fix AC3.5's tolerance; either way, one place.

---

## H8 — Client-controlled sampling (Low, but cheap to close)

AD-8 is parent-based, and the spine's Deferred list says nginx passes `traceparent` through unchanged. So any client
(including the k6 load-test runner) that sends `traceparent: …-01` forces a sampled trace, and `…-00` hides one.
At `p = 1.0` this is harmless; in prod it lets a client choose what is recorded and what id lands in error bodies.
Not a pair clash in the stories, but it is the same "two owners of one decision" pattern (nginx and the app both own the
root of the trace).

**AD fix — AD-8 (add):** "server-api is the trace root for external calls: nginx removes inbound `traceparent` /
`tracestate` (or the app ignores the inbound sampled flag) until nginx spans exist." Then `correlationId`
(nginx `X-Request-Id`) and `traceId` stay clearly separate: one is the edge id, one is ours.

---

## Not holes (checked, fine)

- `correlationId` vs `traceId` in logs: AD-6 + the existing `logWithEventId` (sets `correlationId` = event id in the
  relay) do not clash; `traceId` comes from the MDC that Micrometer fills, and the relay ERROR lines run inside
  `outbox.work`. Consider stating in AD-6 that in batch `correlationId` = outbox event id, so nobody "fixes" it to the
  trace id.
- `RequestIdFilter` is `HIGHEST_PRECEDENCE`, so it wraps Boot's HTTP observation filter; the `correlationId` is in the MDC
  before the span starts. No ordering clash.
- AD-4 "one place" holds: all inherited paths go through `handleExceptionInternal` / `createResponseEntity` in the
  current `ApiExceptionHandler`.
- ARC-07 (feature slices) is unaffected: `config.tracing` and `tracing.outbox` slice cleanly.

## Summary of spine edits requested

| AD | Change |
|---|---|
| AD-1 | Custom spans are Observations only; `Tracer` for read/inject/extract/links |
| AD-3 | Predicate rule = "no current observation", tested both ways; `outbox.claim` opens after the row is returned; business span wraps the transaction |
| AD-4 | Decide sampled vs unsampled `traceId`; test it |
| AD-7 | Feature = business concept; shared span/tag names in `common-logging`; `TracingConstants` = framework names only |
| AD-8 | Stock sampler only (no link-aware sampler); server-api is the trace root (strip inbound `traceparent`) |
| **AD-9 (new)** | `trace_parent` contract: column only, `Propagator` inject/extract only, sampled only, opaque `String` through services, lag computed in SQL at a fixed point |
| **AD-10 (new)** | ArchitectureTest: ARC-05/08 extended, ARC-09/10 added, owned by the baseline story in both apps |
| Seed | Add `server-api/.../tracing/<feature>/impl/`; fix port name (`OutboxTracing` vs `RelayTracing`) |
