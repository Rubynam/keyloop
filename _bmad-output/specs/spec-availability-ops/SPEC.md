---
id: SPEC-availability-ops
companions:
  - cache-read-path.md
  - candidate-selection.md
  - brownfield.md
  - ../spec-check-availability/SPEC.md
  - ../../../planing/Spec04.md
  - ../../../docs/contracts/outbox/README.md
sources: []
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# Availability Ops — cache, invalidation, booking candidates

## Why

Pain + mandate: `GET /api/v1/availability` (`spec-check-availability`) is the hottest read and users re-query it repeatedly, so computing every call from PostgreSQL does not scale (ROADMAP D1). A cache needs event-driven invalidation so a booking shows up within one relay tick, and the booking flow needs ordered (bay, technician) pairs to try. Stale reads may cost UX, never correctness: PostgreSQL constraints stay the only judge of a booking.

## Capabilities

- **CAP-1**
  - **intent:** Repeated identical availability reads are served from Redis.
  - **success:** Integration test: a second identical `GET` within TTL runs no PostgreSQL availability query and returns JSON identical to the first.
- **CAP-2**
  - **intent:** A change to a dealership's day becomes visible on the next read after the relay processes its outbox event.
  - **success:** Integration test: cache warm → insert an outbox row (from `docs/contracts/outbox` samples) + the appointment it describes → relay tick → `avail:ver:{dealer}:{date}` incremented → next `GET` omits the taken slot.
- **CAP-3**
  - **intent:** Concurrent cache misses on the same key cost one PostgreSQL computation.
  - **success:** 20 concurrent `GET`s on a cold key → exactly 1 computation when it finishes within the lock-wait timeout; all 20 return the same slots.
- **CAP-4**
  - **intent:** Reads keep working when Redis is down.
  - **success:** Redis container stopped → `GET` returns 200 computed from PostgreSQL, with a log line and span event; PostgreSQL down → 503.
- **CAP-5**
  - **intent:** A slot that has already started is never served from cache.
  - **success:** With a fixed clock moved past a cached slot's start within TTL, that slot is absent from the response.
- **CAP-6**
  - **intent:** The relay invalidates cache versions with the retry and failure behaviour of Spec04 §4.
  - **success:** Redis errors → `attempts` 1…4, `next_attempt_at` +2 s each, then `failed_at` + English ERROR log (§4.6); bad payload → FAILED on first try; empty date union → processed; two relay instances never process the same row.
- **CAP-7**
  - **intent:** Booking can ask for up to 3 ordered (bay, technician) pairs for a window, or learn why none exist.
  - **success:** Every row of the `candidate-selection.md` examples table passes as a test, including `NOT_SERVICEABLE` and `NONE_FREE`.

## Constraints

- PostgreSQL is the single source of truth; Redis never decides booking validity. Candidate selection reads PostgreSQL only, never the cache.
- Cache is invalidated only by bumping version keys, never by writing or deleting cached values (write-through and direct post-commit invalidation rejected, D1).
- Key layout, TTLs, lock and read flow are fixed in `cache-read-path.md`; the version key is read before PostgreSQL.
- Cached value = one local date's full slot list for one (dealership, service type, vehicle type), exactly the `days[i].slots` shape of `spec-check-availability`; the "now" cut is applied per request, after the cache.
- Lock held by another request → poll the cache until data or lock-wait timeout (default 100 ms, configurable); on timeout compute from PostgreSQL without writing the cache. Never reject.
- Redis unavailable → fail-open to PostgreSQL. PostgreSQL unavailable → 503.
- Relay behaviour is Spec04 §4 verbatim (runtime, tick SQL, retry 1 + 3 × 2 s, error kinds, payload contract, crash safety, messages); this spec turns `scheduler.outbox.relay.enabled` on. The relay never changes a booking.
- All timings (TTL, TTL padding, lock PX, lock wait) are configuration, not code constants.
- Slot-list TTL (≤ 60 s) stays far shorter than the version-key TTL (2 days).
- The TTL padding, the version key and the lock each carry a short B1-English Javadoc saying why, with a worked example (`cache-read-path.md`).
- Lock key includes the version (`avail:lock:{dealerId}:{date}:v{ver}:{serviceTypeId}:{vehicleTypeId}`), so an old version's lock cannot block the rebuild of a new one.
- Lock: acquire with `StringRedisTemplate` `setIfAbsent` (PX 2 s); release only by its owner through a Lua compare-and-delete script run by `StringRedisTemplate.execute`.

## Non-goals

- Rate limiting — its own spec for all `/api/v1` (20 req/s, burst 40, configurable; `deferred-work.md`).
- Staff admin tool / admin-role login; no authentication (`deferred-work.md`).
- `BookingService`, `POST /appointments`, cancel / reschedule endpoints and their outbox writes — booking spec. This spec only provides candidate selection.
- Outbox cleanup job, `LISTEN/NOTIFY` wake-up, CDC, metrics (Spec04 / ROADMAP: deferred).
- Changing the `GET /availability` response or validation rules.

## Success signal

- `./gradlew test` passes for `server-api` and `scheduler-batch`, with Testcontainers (PostgreSQL 18 + Redis 8) integration tests proving CAP-1 to CAP-7; no test mocks Redis or PostgreSQL for those capabilities.

