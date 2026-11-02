# Story — Trace a booking through the outbox relay in Jaeger (operability phase 2)

Status: draft (needs: booking story merged for Task 2, story-tracing-baseline done)
Owner: Winston (Architect) · Date: 2026-11-01
Spine: `planning-artifacts/architecture/architecture-keyloop-tracing-2026-11-01/ARCHITECTURE-SPINE.md` — AD-1 (spans are Observations), AD-7 (names in `TraceAttributes`), AD-9 (`trace_parent` only via `Propagator`, written only when sampled), AD-10 (ArchUnit rules already added by the baseline). Where this story disagrees, the spine wins.
Sources: `story-outbox-relay.md` §11.5, DLD §12 (`12-observability.mermaid`), ROADMAP:150, Spec04 §4, CLAUDE.md §1–§4
Facts checked on branch `refactor/spec05-pr2-pr5`: Spring Boot 4.1.2, no tracing dependency yet, no Jaeger service in `docker-compose.yml`, log pattern has `[correlationId]` only (`common-logging/log4j2-spring.xml:8`), outbox migrations end at V4.

---

## 1. User story

**As an** operator of the scheduler,
**I want to** open one booking in Jaeger and see when its cache invalidation ran, how long it waited, and whether it failed,
**so that** I can answer "why did a customer see a taken slot?" without reading logs from two apps by hand.

Why: phase 1 (`story-outbox-relay.md` Task 7) shows *that* the relay is late or failing. It does not show *which* booking was affected or *where* the time went. The outbox is an async boundary: the booking request ends at `COMMIT`, and the relay runs later in another process. Without help, Jaeger shows two unrelated traces.

---

## 2. The picture

```
Trace A (server-api, POST /api/v1/appointments)          Trace B (scheduler-batch, relay)
 http POST /appointments                                  outbox.work  ── link ──► Trace A / span "outbox.write"
  ├─ jdbc INSERT appointments                               │  event.id, event.type, attempts, outcome
  └─ outbox.write                                           │  relay.lag.ms = now − created_at
      └─ jdbc INSERT outbox_events                          ├─ redis EVALSHA bump-version ×N
          trace_parent = "00-<traceA>-<spanW>-01"           └─ jdbc UPDATE outbox_events
```

- **Link, not parent.** The relay starts a new trace (Trace B) and links it to the booking span. A parent would stretch Trace A over seconds and mix retries from many ticks into one request. A link keeps both traces short, and Jaeger shows the link as a clickable reference.
- **No span for an empty tick.** The relay ticks every 500 ms. A span per empty tick would be about 170 000 empty spans per day per instance. Spans start only when a row is claimed.

---

## 3. Tasks

### Task 1 — Tracing baseline in both apps → moved

Done by [`story-tracing-baseline.md`](story-tracing-baseline.md): Jaeger 2.20.0, `spring-boot-starter-opentelemetry`, JDBC/Redis spans, `traceId` in logs and errors, no orphan relay spans. Naming and port rules for Tasks 2–3: tracing spine AD-3, AD-7.

### Task 2 — Store the trace context in the outbox row (server-api)

- Migration `V5__outbox_trace_parent.sql` (owned by server-api, like V4):

```sql
ALTER TABLE outbox_events ADD COLUMN trace_parent TEXT;   -- W3C traceparent, NULL when no trace was active
```

- The outbox writer (from `story-book-appointment.md`, Task 3) opens a short span `outbox.write` and stores its W3C `traceparent` string in `trace_parent`. It is written in the same transaction as the event, so it can never point at a booking that did not commit.
- `NULL` is valid: seed rows (`seeding/sql/13_outbox_events.sql`), old rows, and writes with no active trace.
- The relay reads the column but does not depend on it.

**Acceptance criteria**
- AC2.1 Given a booking request with tracing on, when the outbox row is written, then `trace_parent` matches `^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$` and its trace id equals the request's trace id.
- AC2.2 Given a write with no active trace, then `trace_parent` is `NULL` and the booking still succeeds.

**Dependency:** the booking story writes the outbox row. If it is not merged yet, Task 2 goes into that story instead, and this story only adds the column.

### Task 3 — Relay spans with a link to the booking (scheduler-batch)

Spans, named with constants in `RelayConstants` (CLAUDE.md §3):

| Span | Starts | Low-cardinality tags | High-cardinality tags |
|---|---|---|---|
| `outbox.claim` | each `claimNext()` that returns a row | `outcome = claimed \| exhausted` | `event.id`, `attempts` |
| `outbox.work` | each `work(row)`, with a **link** to `trace_parent` when present | `event.type`, `outcome = processed \| retry \| failed \| invalid \| relock_skipped` | `event.id`, `dealership.id`, `dates.count`, `attempts`, `relay.lag.ms` |

- `relay.lag.ms` = PostgreSQL `now() − created_at` at the start of the work. This is the "booking → cache invalidated" delay, the main number this story exists for. It comes from SQL time, never the JVM clock (Spec04 §4.2 rule).
- A bad `trace_parent` string is ignored: no link, one DEBUG log, the work goes on.
- Layering (CLAUDE.md §1): the service calls a port `RelayTracing` (interface) with a Micrometer Tracing implementation. The service never touches the tracer API. New package `tracing/outbox/` (interface) + `tracing/outbox/impl/`; add it to the CLAUDE.md layout in the same change.
- No customer data in any tag (DLD §12 "no PII"). `dealership.id` is a business id, not personal data.
- The ERROR logs from Spec04 §4.6 are written inside `outbox.work`, so they carry its `traceId`.

**Acceptance criteria**
- AC3.1 Given a booking traced in Trace A, when the relay processes its event, then Jaeger shows an `outbox.work` span in a new trace with a link to Trace A's `outbox.write` span, and `outcome = processed`.
- AC3.2 Given Redis is down, when the event is tried 4 times, then Jaeger shows 4 `outbox.work` spans with `outcome = retry, retry, retry, failed`, all linked to the same booking span.
- AC3.3 Given no due rows, when the relay ticks for 1 minute, then no relay span is exported.
- AC3.4 Given `trace_parent` is `NULL` or not valid, when the relay processes the row, then `outbox.work` has no link and the row is processed normally.
- AC3.5 Given a processed event, then `relay.lag.ms` on its span is within 50 ms of `processed_at − created_at` in the table.

### Task 4 — Tests

| Test | Covers |
|---|---|
| server-api IT with an in-memory span exporter (from the tracing test support of the chosen stack) | AC1.3, AC2.1, AC2.2 |
| scheduler-batch IT with an in-memory span exporter, reusing `OutboxRelayIT` rows | AC3.1–AC3.5 |
| compose smoke check (manual, documented in the README) | AC1.1, AC1.2 |

---

## 4. Files

```
docker-compose.yml                                          + jaeger service
common-logging/src/main/resources/log4j2-spring.xml         + %X{traceId} %X{spanId}
server-api/src/main/resources/db/migration/V5__outbox_trace_parent.sql   (new)
server-api/.../outbox writer (booking story)                + outbox.write span, trace_parent
scheduler-batch/.../tracing/outbox/RelayTracing             (new, interface)
scheduler-batch/.../tracing/outbox/impl/MicrometerRelayTracing (new)
scheduler-batch/.../model/outbox/OutboxEvent                + traceParent, createdAt (for lag)
scheduler-batch/.../repository/outbox/impl/JdbcOutboxEventRepository  claimNext reads trace_parent, now() − created_at
scheduler-batch/.../constant/outbox/RelayConstants          + span and tag names
CLAUDE.md                                                   + tracing/ in the layout
```

---

## 5. Out of scope

- Metrics, Prometheus, Grafana, OTel Collector (deferred, ROADMAP:150, DLD §12).
- Tail sampling ("keep every trace with a FAILED relay span").
- JSON log format (DLD §12 target). The pattern only gets `traceId` / `spanId` here.
- Tracing the cleanup Batch job.

---

## 6. Risks and open points

| # | Point | Proposal |
|---|---|---|
| T1 | **Sampling breaks links.** The relay trace is a new root, so it is sampled on its own. If Trace A was not kept, the link points at nothing. | Local: `probability: 1.0`. Production: decide with real volume. Option: sample `outbox.work` when the linked context has the sampled flag (`-01`); it is in the `traceparent` string. |
| T2 | **Span volume.** One booking ≈ 1 HTTP + ~5 DB/Redis spans + 1 `outbox.write` + 2 relay spans per try. | Fine for booking volume. Empty ticks make no spans (AC3.3). |
| T3 | **Boot 4 tracing setup is new to the team.** | Task 1 spike with a 1-day timebox. If it overruns, ship Task 1 alone first. |
| T4 | **Two ids in logs** (`correlationId`, `traceId`). | Keep both. `correlationId` follows nginx and the client; `traceId` follows Jaeger. Document this in `common-logging`. |

---

## 7. Definition of done

- AC1.x, AC2.x, AC3.x pass (Task 4); the compose smoke check is written in the README.
- `ArchitectureTest` green (rules ARC-09/10 come from story-tracing-baseline; this story adds classes only).
- `story-outbox-relay.md` §11.6: Operability moves to 8/10.
- T1 decided for production, or written down as a deploy-time decision.
