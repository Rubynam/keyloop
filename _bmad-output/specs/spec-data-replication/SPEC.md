---
id: SPEC-data-replication
companions:
  - ../../planning-artifacts/architecture/architecture-keyloop-replication-2026-11-01/ARCHITECTURE-SPINE.md
sources: []
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# Data replication — Postgres primary/replica, Redis master/replica

## Why

Vision + pain: the scale demo runs single-node Postgres and Redis, so read load cannot be offloaded and a node loss stops the system. Adding one replica per store shows read scaling and manual recovery without changing booking correctness. The architecture spine (companion) fixes the invariants as `AD-1`…`AD-9`; this spec cites them by ID.

## Capabilities

- **CAP-1**
  - **intent:** Postgres runs one primary and one async streaming replica that bootstrap from empty volumes (AD-1, AD-8).
  - **success:** After `docker compose down -v && up`, `pg_stat_replication` on the primary shows the replica `streaming`, and a row inserted on the primary is readable on the replica.
- **CAP-2**
  - **intent:** Redis runs one master and one cache-only replica (AD-1).
  - **success:** `INFO replication` shows `role:master` with one connected slave; a key `SET` on the master is readable on the replica; `SET` on the replica is rejected.
- **CAP-3**
  - **intent:** Availability reads are served from the Postgres replica while booking and all framework users stay on the primary (AD-3, AD-4).
  - **success:** `GET /api/v1/availability` cache-miss queries hit the replica (visible in replica `pg_stat_activity`/logs); booking, Flyway, ShedLock and Batch queries never do; ARC-09 passes in both apps.
- **CAP-4**
  - **intent:** The availability cache reflects a booking once the relay has processed it, even with replica lag (AD-5).
  - **success:** Test with injected replica lag: booking commits, relay bumps the version only after the outbox row is visible on the replica, and the next availability read shows the slot taken; a gate connection on the primary is never counted as visible.
- **CAP-5**
  - **intent:** The cache reads slot lists from the Redis replica without serving stale versions or weakening the rebuild lock (AD-6).
  - **success:** Unit tests per call site show version/lock/write/re-read/poll on master and only the first slot read on replica; a replica miss re-reads master before locking.
- **CAP-6**
  - **intent:** Losing a Postgres or Redis replica degrades reads to the primary without failing readiness (AD-7).
  - **success:** Integration test stops the replica: readiness stays UP, availability still answers, `dbReplica`/`redisReplica` report DOWN outside readiness.
- **CAP-7**
  - **intent:** An operator fails over Postgres or Redis manually with one script, with no window where two nodes accept writes (AD-2).
  - **success:** Running the failover script during the book-ramp load test leaves exactly one writable node, bookings resume after the step, and the old node rejoins as replica on the next `up`.
- **CAP-8**
  - **intent:** Every consumer locates data nodes by node list instead of a single host (AD-9).
  - **success:** `DB_HOST`/`REDIS_HOST` no longer appear in compose, k8s, simulator, scripts or app config; all start against `DB_HOSTS`/`REDIS_NODES`.

## Constraints

- CLAUDE.md layering, constants and Javadoc rules apply to every Java change; replica awareness stays in config and repository/cache/store implementations.
- Booking correctness stays with Postgres primary; a stale cache may cost a 409, never a double booking.
- Accepted residual: stale availability cache for at most the slot TTL (60s) when a reader's connection sits on a lagging node (AD-5).
- Moving to this topology needs one `docker compose down -v`; data is re-seeded afterwards.
- Official `postgres:18-alpine` / `redis:8-alpine` images only; no new HA component.

## Non-goals

- Automatic failover (Sentinel, Patroni, repmgr).
- More than one replica per store.
- LSN-based read-your-writes guard on the reader.
- Data-tier StatefulSets in `infra/k8s` (only the AD-9 env change applies there).
- Replication-lag metrics and alerts.
- Testcontainers with a real replica.

## Success signal

- On the demo compose stack, the book-ramp load test runs with availability misses served by the Postgres replica and slot reads by the Redis replica; stopping either replica keeps the API ready, and a scripted failover leaves one writable node with bookings resuming.
