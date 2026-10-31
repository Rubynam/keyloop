- source_spec: `_bmad-output/implementation-artifacts/spec-read-availability.md`
  summary: Redis cache-aside for GET /availability (versioned key, TTL + jitter, single-flight lock, fail-open) per ROADMAP D1 / DLD §4.
  evidence: User chose a DB-only read for the first availability story; no outbox relay exists yet to invalidate cached slots.
