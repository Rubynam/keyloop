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
