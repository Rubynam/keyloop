- source_spec: `_bmad-output/implementation-artifacts/spec-read-availability.md`
  summary: Redis cache-aside for GET /availability (versioned key, TTL + jitter, single-flight lock, fail-open) per ROADMAP D1 / DLD §4.
  evidence: User chose a DB-only read for the first availability story; no outbox relay exists yet to invalidate cached slots.
- source_spec: `_bmad-output/specs/spec-check-availability/SPEC.md`
  summary: Staff check dealership schedules through an admin tool, or by logging in with an admin role (authentication + role-based access for staff).
  evidence: User deferred it on 2026-10-31; the availability API is public and has no auth or admin view yet.
- source_spec: `_bmad-output/specs/spec-availability-ops/SPEC.md`
  summary: Rate limiting for all /api/v1 in its own spec — Redis token bucket per client IP (getRemoteAddr under forward-headers-strategy framework, Spec02 API-14), 20 req/s, burst 40, configurable; 429 ProblemDetail; fail-open when Redis is down.
  evidence: User moved it out of spec-availability-ops on 2026-10-31 because it covers every endpoint, not only availability.
- source_spec: none
  summary: Spec05 PR2 — move availability across all layers (controller/service/repository/cache interfaces + impls, model/dto/config moves, Lua script to resources, Javadoc).
  evidence: Spec05 §9 delivers five independently mergeable PRs; user chose PR1 (shared layer-root code) first on 2026-11-01.
- source_spec: none
  summary: Spec05 PR3 — move booking (CandidateSelector, CandidateRepository, CandidateResult) to layered packages behind interfaces.
  evidence: Spec05 §9 split; user chose PR1 first on 2026-11-01.
- source_spec: none
  summary: Spec05 PR4 — split scheduler-batch (OutboxRelay into schedule/service/repository/store, heartbeat service + probe, JobNames/SchedulingConstants).
  evidence: Spec05 §9 split; user chose PR1 first on 2026-11-01.
- source_spec: none
  summary: Spec05 PR5 — ArchUnit ArchitectureTest (ARC-01..08) in both apps, CLAUDE.md layout update (RF-14), RF-11 grep clean.
  evidence: Spec05 §9 split; user chose PR1 first on 2026-11-01.
- source_spec: `_bmad-output/implementation-artifacts/spec-05-pr1-shared-layer-root.md`
  summary: Add .DS_Store and loadtest/__pycache__/ to .gitignore.
  evidence: Both show as untracked junk; .gitignore is also edited by PR #6, so do it after that merges.
- source_spec: `_bmad-output/implementation-artifacts/spec-05-pr2-pr5-layered-refactor.md`
  summary: Give the availability cache port its own model slot type so `cache/` stops depending on the HTTP DTO `AvailabilityResponse.Slot`.
  evidence: CLAUDE.md §1 says DTOs are HTTP only; the dependency pre-dates Spec05 and needs a model type plus mapping in the service.
- source_spec: `_bmad-output/implementation-artifacts/spec-05-pr2-pr5-layered-refactor.md`
  summary: Add a contract test (or shared constant) proving server-api and scheduler-batch build the same `avail:ver:{dealer}:{date}` key.
  evidence: The prefix is defined in AvailabilityConstants and RelayConstants separately; drift would leave the cache silently stale. Pre-existing as literals.
- source_spec: `_bmad-output/implementation-artifacts/spec-05-pr2-pr5-layered-refactor.md`
  summary: Validate `scheduler.outbox.relay.batch-size` as positive so `OutboxRelay.relay()` cannot busy-loop on 0.
  evidence: `while (tick() == batchSize)` never ends when batch-size is 0; no @Validated/@Positive on OutboxRelayProperties. Pre-existing loop.
- source_spec: `_bmad-output/implementation-artifacts/spec-05-pr2-pr5-layered-refactor.md`
  summary: Add `constant/ping/PingConstants` and `resources/redis/release-lock.lua` to the CLAUDE.md §1 layout tree.
  evidence: Both were added by Spec05 PR2/PR5 but the tree written in PR5 omits them.
- source_spec: `planing/Spec07.md`
  summary: Pick pairs herd — every concurrent request for one slot gets the same 3 pairs (deterministic, AC13), so some answer 409 while other pairs are still free. Candidate fix: rotate the pair list by `hash(idempotencyKey) % n` (same key → same order).
  evidence: Raised on 2026-11-01 while reviewing Spec07 §7.1 / §13 "no lock on the candidate read". User deferred it: no load test numbers yet. Decide with Spec06 `race` / `stress` (rate of 409 while free pairs remain).
- source_spec: `planing/Spec07.md`
  summary: `JdbcCandidateRepository.countAppointments` filters `technician_id IN (...) AND start_time` with no `dealership_id`, so `idx_appointments_dealer_start` is not used and it reads each technician's full history. Candidate fix: add `dealership_id = :dealershipId` or an index `(technician_id, start_time)`.
  evidence: Raised on 2026-11-01. User deferred it until there is data. Check with `EXPLAIN ANALYZE` on a seeded table with months of past appointments (needs seed data, not concurrent load).
- source_spec: `planing/Spec07.md`
  summary: Connection pool pressure on booking spikes — 5–7 reads (D8) + up to 3 transactions per request may exhaust the Hikari pool → 503. Options: merge the candidate reads into one SQL (D8) or tune pool size.
  evidence: Raised on 2026-11-01. User deferred it: no load test numbers yet. Decide with Spec06 `stress` (pool wait time, 503 rate, p95).
