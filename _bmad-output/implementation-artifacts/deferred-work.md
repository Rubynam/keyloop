- source_spec: `_bmad-output/implementation-artifacts/spec-read-availability.md`
  summary: Redis cache-aside for GET /availability (versioned key, TTL + jitter, single-flight lock, fail-open) per ROADMAP D1 / DLD §4.
  evidence: User chose a DB-only read for the first availability story; no outbox relay exists yet to invalidate cached slots.
- source_spec: `_bmad-output/specs/spec-check-availability/SPEC.md`
  summary: Staff check dealership schedules through an admin tool, or by logging in with an admin role (authentication + role-based access for staff).
  evidence: User deferred it on 2026-10-31; the availability API is public and has no auth or admin view yet.
- source_spec: `_bmad-output/specs/spec-availability-ops/SPEC.md`
  summary: Rate limiting for all /api/v1 in its own spec — Redis token bucket per client IP (getRemoteAddr under forward-headers-strategy framework, Spec02 API-14), 20 req/s, burst 40, configurable; 429 ProblemDetail; fail-open when Redis is down.
  evidence: User moved it out of spec-availability-ops on 2026-10-31 because it covers every endpoint, not only availability.
