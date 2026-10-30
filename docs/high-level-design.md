# High-Level Design — Unified Service Scheduler

> Scenario A · Keyloop Technical Assessment
> Scope: system context, containers, and the three main flows. Implementation details live in [detail-level-design.md](detail-level-design.md).
> 🔍 Zoomable diagrams: open [`diagrams/viewer.html`](diagrams/viewer.html) in a browser (regenerate with `python3 docs/diagrams/build_viewer.py`).

---

## 1. System Context

Who uses the system and what it talks to.

📊 Diagram: [`diagrams/hld/01-system-context.mermaid`](diagrams/hld/01-system-context.mermaid)

---

## 2. Container Diagram

📊 Diagram: [`diagrams/hld/02-container.mermaid`](diagrams/hld/02-container.mermaid)

| Container | Role |
|---|---|
| **Scheduler API** | Stateless REST service. Validates requests, computes availability, books / cancels appointments. Scales horizontally. |
| **Outbox Relay** | Reads committed `outbox_events` and invalidates the cache (bumps version keys). Several instances can run safely. |
| **PostgreSQL** | Single source of truth. Exclusion constraints make double-booking impossible. Also acts as the outbox queue. |
| **Redis** | Read-side cache for availability + rate limiting. Never decides whether a booking is valid. |
| **Jaeger** | Distributed tracing (OTLP, sent directly from the apps). Logs stay as structured JSON on stdout. Metrics (Prometheus / Grafana) are deferred — see ROADMAP. |

---

## 3. Main Flows (high level)

### 3.1 Check availability — read path (cache-aside)

📊 Diagram: [`diagrams/hld/03-1-flow-check-availability.mermaid`](diagrams/hld/03-1-flow-check-availability.mermaid)

### 3.2 Book appointment — write path

📊 Diagram: [`diagrams/hld/03-2-flow-book-appointment.mermaid`](diagrams/hld/03-2-flow-book-appointment.mermaid)

### 3.3 Cache invalidation — event-driven

📊 Diagram: [`diagrams/hld/03-3-flow-cache-invalidation.mermaid`](diagrams/hld/03-3-flow-cache-invalidation.mermaid)

---

## 4. Key Design Principles

📊 Diagram: [`diagrams/hld/04-design-principles.mermaid`](diagrams/hld/04-design-principles.mermaid)
