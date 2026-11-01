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
- source_spec: `_bmad-output/implementation-artifacts/spec-07-book-appointment.md`
  summary: Load-test case BK-01 expects a `traceId` field on the 409 ProblemDetail, but the API does not emit one, so BK-01 fails on every 409.
  evidence: Assertion `{"path": "traceId", "exists": true}` already in `loadtest/expected/30-booking.json:29` at baseline 251a8b0; adding `traceId` belongs to the tracing work (story-outbox-tracing / Spec06), not Spec07.
- source_spec: none
  summary: Outbox relay Task 6 (OP3) — seed a new `avail:ver:{dealershipId}:{date}` key with Redis `TIME` via `bump-version.lua` so a restarted counter never repeats an old version.
  evidence: Split from story-outbox-relay on 2026-11-01; independently shippable (only changes the bump script + AC6.x tests), not needed for the claim/relock correctness fix.
- source_spec: none
  summary: Outbox relay Task 7 — health log lines (`OutboxRelayHealthLog`, backlog/eviction checks, `health-log-interval`, `backlog-warn-age`).
  evidence: Split from story-outbox-relay on 2026-11-01; independently shippable operability work (new scheduled class + repository/store reads), no coupling to the claim/relock change.
- source_spec: `_bmad-output/implementation-artifacts/spec-outbox-relay-claim-relock.md`
  summary: Outbox relay Task 3 — `@Validated` `OutboxRelayProperties` (`@Positive` batch-size, `@PositiveOrZero` max-retries, `@DurationMin(millis = 1)` durations, add validation starter) with AC3.1 (`OutboxRelayPropertiesTest`) and AC3.2 (`max-retries: 1`, `retry-delay: 500ms` for Redis-down and crash paths).
  evidence: Split on 2026-11-01 at the spec token gate (user asked for a smaller spec); independent of the claim/relock fix.
- source_spec: `_bmad-output/implementation-artifacts/spec-outbox-relay-claim-relock.md`
  summary: Outbox relay AC-F.3 — a version key holding a non-integer (`ERR value is not an integer`) fails the row on the first try with `INVALID_VERSION_KEY_LOG` instead of retrying (new `InvalidVersionKeyException` from the store).
  evidence: Split on 2026-11-01 at the spec token gate; until then such a row follows the Redis-error retry path (3 retries, then FAILED), which is safe but slower.
- source_spec: `_bmad-output/implementation-artifacts/spec-outbox-relay-claim-relock.md`
  summary: `AvailabilityVersionStore.bump(dealershipId, List<LocalDate>)` — all dates of one row in one Redis pipeline; do it together with Task 6 (Lua seed script per date).
  evidence: Split on 2026-11-01 at the spec token gate; a performance change (one round trip per row), not needed for crash counting, and it shares code with Task 6.
- source_spec: `_bmad-output/implementation-artifacts/spec-outbox-relay-claim-relock.md`
  summary: `OutboxRelay.relay()` starts another tick when a Redis error stops `tick()` on the `batch-size`-th row (with `batch-size: 1`, every Redis error) — return a "stopped" signal or exclude the failed row so the loop waits for `poll-delay`.
  evidence: Review pass 1 (V2); pre-existing — baseline `tick()` also returned `rows.size()` after a Redis error.
- source_spec: `_bmad-output/implementation-artifacts/spec-outbox-relay-claim-relock.md`
  summary: `batch-size: 0` (or negative) makes `OutboxRelay.relay()` spin forever (`tick() == 0 == batchSize`); fixed by the deferred Task 3 `@Positive` validation.
  evidence: Review pass 1 (E3); pre-existing loop condition, same at baseline.
- source_spec: `_bmad-output/implementation-artifacts/spec-outbox-relay-properties-validation.md`
  summary: A missing `scheduler.outbox.relay.max-retries` binds to 0 (primitive `int`) and passes `@PositiveOrZero`, so retries turn off silently — use `Integer` + `@NotNull` or an explicit default.
  evidence: Review pass 1 (B1); record constructor binding defaults missing primitives to 0, same before Task 3. Shipped application.yml sets the value.
  resolution: Fixed by `spec-outbox-properties-hardening` — `batchSize` / `maxRetries` are `Integer` + `@NotNull`, so a missing key stops startup.
- source_spec: `_bmad-output/implementation-artifacts/spec-outbox-relay-properties-validation.md`
  summary: Validate `OutboxCleanupProperties` (`@NotBlank` cron, `@DurationMin` on the three retentions) — a 0 `failed-retention` would delete FAILED rows at once.
  evidence: Review pass 1 (B2); pre-existing, excluded from Task 3 by its intent (Never section).
  resolution: Fixed by `spec-outbox-properties-hardening` — `OutboxCleanupProperties` is `@Validated` (`@NotBlank` cron, `@NotNull @DurationMin(millis = 1)` retentions), checked even when disabled.
- source_spec: `_bmad-output/implementation-artifacts/spec-outbox-relay-properties-validation.md`
  summary: Add upper bounds to relay settings (`@Max` on max-retries/batch-size, `@DurationMax` on durations) — `max-retries=Integer.MAX_VALUE` overflows `maxRetries() + 1` in `OutboxRelayServiceImpl` and fails every row; huge durations overflow `toMillis()`.
  evidence: Review pass 1 (B3/E1/E2); needs absurd values, overflow pre-dates Task 3. Pick limits with ops (e.g. version-key-ttl vs cache TTL).
  resolution: Fixed by `spec-outbox-properties-hardening` — `@Max` / `@DurationMax` limits in `RelayConstants` (batch-size ≤ 1000, max-retries ≤ 100, poll/retry delay ≤ 1h, version-key-ttl ≤ 30d).
- source_spec: `_bmad-output/implementation-artifacts/spec-outbox-properties-hardening.md`
  summary: `scheduler.outbox.cleanup.cron` is only `@NotBlank` — a malformed cron (`abc`, 5 fields) starts the app; add a `CronExpression.isValidExpression` constraint when the cleanup job is implemented.
  evidence: Review pass 1 (#2); excluded by the spec's frozen Never; pre-existing, nothing schedules the cron yet.
- source_spec: `_bmad-output/implementation-artifacts/spec-outbox-properties-hardening.md`
  summary: Cleanup retentions have no upper bound — add `@DurationMax` once the cleanup job converts them (`now().minus(...)` / `toMillis()`).
  evidence: Review pass 1 (#3), unverified (would be medium): `OutboxCleanupJobConfig` is a placeholder, so no overflow path exists yet; settle when the job's delete steps are written.
- source_spec: `_bmad-output/implementation-artifacts/spec-outbox-properties-hardening.md`
  summary: `scheduler.outbox.relay.version-key-ttl` accepts values below the server-api availability cache TTL (`30s`), so a version key can expire before cached slot lists and stale availability is served — raise the floor or document the coupling with ops.
  evidence: Review pass 1 (#4); excluded by the spec's frozen Never (no cross-module check); pre-existing.
- source_spec: `_bmad-output/implementation-artifacts/spec-outbox-properties-hardening.md`
  summary: `enabled` in `OutboxRelayProperties` / `OutboxCleanupProperties` is primitive `boolean`, so a missing key binds to `false` with no error (same class as B1) — consider `@NotNull Boolean` or document that missing means off.
  evidence: Review pass 1 (#5); pre-existing; `@ConditionalOnProperty` on `scheduler.outbox.relay.enabled` already treats a missing key as off.
- source_spec: `_bmad-output/implementation-artifacts/spec-outbox-properties-hardening.md`
  summary: `OutboxRelayIT.twoInstancesNeverProcessTheSameRow` is flaky — line 207 sees version 0 for one date while the handled total is 40, so a row can be counted as handled without its version bump under two concurrent instances (claim/relock path).
  evidence: Found during verification on 2026-11-01: fails 3/8 runs on baseline `efbb5b1` and 1/5 on this branch with the same assertion; this spec does not touch the relay loop.
  resolution: Fixed 2026-11-01 — code bug, not the test. Under READ COMMITTED, instance B's `lockNextDue` (`SKIP LOCKED`) could read row E from a snapshot taken before A's TX A committed, lock E, fail the re-check (lease now in the future) and keep that lock until B's TX A ended. A's `relock(E, …) FOR UPDATE SKIP LOCKED` then returned false, so `tick()` counted E as claimed but never bumped it (the lease kept E out of later ticks). `relock` now uses plain `FOR UPDATE` (waits; ownership is still decided by `attempts`/state); AC2.1 tests in `OutboxRelayRelockIT` now expect a wait (process when the holder changed nothing, skip when it took the next try).
