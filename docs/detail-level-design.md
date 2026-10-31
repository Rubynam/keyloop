# Detail-Level Design — Unified Service Scheduler

> Scenario A · Keyloop Technical Assessment
> Builds on [high-level-design.md](high-level-design.md). Items marked **TBD** are still open decisions (see ROADMAP).
> 🔍 Zoomable diagrams: open [`diagrams/viewer.html`](diagrams/viewer.html) in a browser (regenerate with `python3 docs/diagrams/build_viewer.py`).

---

## 1. Component Diagram (inside the Scheduler API)

📊 Diagram: [`diagrams/dld/01-component.mermaid`](diagrams/dld/01-component.mermaid)

| Component | Responsibility |
|---|---|
| `RateLimitFilter` | Token bucket per client in Redis → `429` when exceeded. Fail-open if Redis is down. |
| `BookingValidator` | Entities exist, vehicle belongs to customer, start in the future, slot within business hours. |
| `AvailabilityService` | Computes free slots / free (bay, technician) candidates for a window **for the vehicle's type** (bays and technicians that take it, D2). Uses the cache for list queries. |
| `ResourceSelector` | Orders candidates (strategy **TBD**: lowest id, least loaded, …). |
| `BookingService` | Transaction: insert appointment + outbox event; retries next candidate on exclusion violation. |
| `AvailabilityCache` | Versioned cache-aside over Redis with TTL + random padding, stampede protection (single-flight lock per version, Lua owner release). Spec: [spec-availability-ops](../_bmad-output/specs/spec-availability-ops/SPEC.md). |
| `OutboxRelay` | Reads committed events with `SKIP LOCKED`, bumps version keys, marks processed. |

---

## 2. Data Model (ERD)

📊 Diagram: [`diagrams/dld/02-erd.mermaid`](diagrams/dld/02-erd.mermaid)

- Every id is a **UUIDv7** (`DEFAULT uuidv7()`, Postgres 18): time-ordered, so new rows append to the primary-key index.
- Vehicle types are **data** (`vehicle_types`), models live in `car_info` (name, type, brand); bays and technicians link to vehicle types M:N (ROADMAP D2).
- Schema: [`V2__domain_schema.sql`](../server-api/src/main/resources/db/migration/V2__domain_schema.sql) · spec: [Spec03](../planing/Spec03.md).

### Constraints & indexes

📊 Diagram: [`diagrams/dld/02-constraints.mermaid`](diagrams/dld/02-constraints.mermaid)

---

## 3. API Contract

📊 Diagram: [`diagrams/dld/03-api-contract.mermaid`](diagrams/dld/03-api-contract.mermaid)

---

## 4. Sequence — Check Availability (cache-aside + version key)

📊 Diagram: [`diagrams/dld/04-seq-check-availability.mermaid`](diagrams/dld/04-seq-check-availability.mermaid)

---

## 5. Sequence — Book Appointment

📊 Diagram: [`diagrams/dld/05-seq-book-appointment.mermaid`](diagrams/dld/05-seq-book-appointment.mermaid)

> Booking never reads availability from the cache — candidates are always loaded from PostgreSQL, and the exclusion constraint is the final guard.

---

## 6. Sequence — Cancel / Reschedule

📊 Diagram: [`diagrams/dld/06-seq-cancel-reschedule.mermaid`](diagrams/dld/06-seq-cancel-reschedule.mermaid)

---

## 7. Sequence — Outbox Relay (cache invalidation)

📊 Diagram: [`diagrams/dld/07-seq-outbox-relay.mermaid`](diagrams/dld/07-seq-outbox-relay.mermaid)

---

## 8. Availability Computation

📊 Diagram: [`diagrams/dld/08-availability-computation.mermaid`](diagrams/dld/08-availability-computation.mermaid)

Overlap rule (half-open): `existing.start < new.end AND new.start < existing.end`.

---

## 9. State Machines

### Appointment

📊 Diagram: [`diagrams/dld/09-state-appointment.mermaid`](diagrams/dld/09-state-appointment.mermaid)

### Outbox event

📊 Diagram: [`diagrams/dld/09-state-outbox-event.mermaid`](diagrams/dld/09-state-outbox-event.mermaid)

---

## 10. Redis Key Layout

📊 Diagram: [`diagrams/dld/10-redis-keys.mermaid`](diagrams/dld/10-redis-keys.mermaid)

---

## 11. Error Handling

📊 Diagram: [`diagrams/dld/11-error-handling.mermaid`](diagrams/dld/11-error-handling.mermaid)

All errors are returned as RFC 7807 `ProblemDetail` with a `traceId`.

---

## 12. Observability

📊 Diagram: [`diagrams/dld/12-observability.mermaid`](diagrams/dld/12-observability.mermaid)

Current scope is **tracing only**: apps export spans via OTLP straight to Jaeger (no collector). Structured JSON logs carry the `traceId` for correlation.
Metrics (Prometheus + Grafana, plus an OTel Collector to fan out) are **deferred** — see ROADMAP.

---

## 13. Deployment (local / docker-compose)

📊 Diagram: [`diagrams/dld/13-deployment.mermaid`](diagrams/dld/13-deployment.mermaid)
