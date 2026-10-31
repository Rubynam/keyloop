# Cache Read Path — `GET /availability`

Versioned cache-aside over Redis (ROADMAP D1, DLD §4, §10). One cache entry = one local date.

## Keys

| Key | Value | TTL | Written by |
|---|---|---|---|
| `avail:ver:{dealerId}:{date}` | INT version | 2 days (`EXPIRE` on bump) | relay `INCR` only |
| `avail:{dealerId}:{date}:v{ver}:{serviceTypeId}:{vehicleTypeId}` | JSON array of `{start, end}` — full day, no "now" cut | 30 s + random padding 0–30 s | read path on miss |
| `avail:lock:{dealerId}:{date}:v{ver}:{serviceTypeId}:{vehicleTypeId}` | random owner token (UUID) of one `GET` call | PX 2 s | read path on miss |

- `{date}` is the dealership-local date (`yyyy-MM-dd`), same as outbox payload dates.
- No VIN or customer data in keys: availability depends only on vehicle type (D2).
- Version key is per (dealership, date): one bump invalidates every service type and vehicle type of that day.

## Per-date flow

```
1. ver = GET avail:ver:{dealer}:{date}            (missing → 0)   — BEFORE any PostgreSQL read
2. v   = GET avail:{dealer}:{date}:v{ver}:{st}:{vt}
   hit  → go to 5
3. SET avail:lock:…:v{ver}:… <token> NX PX 2000        (StringRedisTemplate.opsForValue().setIfAbsent)
   acquired → compute from PostgreSQL → SET value EX ttl+padding
              → release with Lua: if GET(lock) == token then DEL(lock)   (StringRedisTemplate.execute(RedisScript))
              → go to 5
   not acquired → poll step 2 until value found or lock-wait (100 ms) elapses
                  found   → go to 5
                  timeout → compute from PostgreSQL, do NOT write the cache → go to 5
4. Any Redis error at 1–3 → compute from PostgreSQL (fail-open), log WARN + span event
5. Drop slots with start < now (request clock) → return
```

Reading the version first means a bump that lands during the computation makes the stored entry already stale under an old version, never a new version holding old data.

## Why padding and version (must appear as short B1-English Javadoc with an example)

- **TTL padding:** keys built at the same moment (e.g. right after a version bump) would all expire at the same second and hit PostgreSQL together. A random 0–30 s padding spreads the expiry. Example: three keys built at 10:00:00 expire at 10:00:41, 10:00:33, 10:00:57 instead of all at 10:00:30.
- **Version key:** one booking changes every service type and vehicle type of that dealership day, so deleting each slot-list key is impossible to do completely. Bumping one number per (dealership, date) makes every old key unreachable at once. Example: `avail:ver:hub:2026-11-05` goes 3 → 4, so reads move from `…:v3:…` to `…:v4:…` and the `v3` keys expire unused.
- Slot-list TTL (≤ 60 s) must stay far shorter than the version-key TTL (2 days): when an expired version reads as 0 again, every old `:v0:` entry is long gone.
- **Lock (request coalescing):** the slot list does not depend on who asks, so all users of one key see the same data. When the key is missing, one `GET` computes it and the others wait and reuse it. The lock protects the database from duplicate work, not the data. Example (put in the Javadoc):

  ```
  Without lock — key just expired, 50 users ask at once:
    user 1..50 → miss → 50 identical DB queries, same result
  With lock:
    user 1     → miss → takes lock → queries DB → writes cache
    user 2..50 → miss → sees lock → waits ≤ 100 ms → reads cache
    → the DB gets 1 query
  ```

- **Lua release:** the lock may expire while its owner is still slow, and another `GET` may take it. Deleting without checking would remove that other call's lock. The script compares the token and deletes in one step, so nothing can run in between. The Javadoc must say this in short B1 English.
- **Version in the lock key:** a lock for `v3` must not block the rebuild of `v4` after a bump.

## Configuration (`server-api`)

| Key | Default |
|---|---|
| `scheduler.availability.cache.enabled` | `true` |
| `scheduler.availability.cache.ttl` | `30s` |
| `scheduler.availability.cache.ttl-padding` | `30s` |
| `scheduler.availability.cache.lock-ttl` | `2s` |
| `scheduler.availability.cache.lock-wait` | `100ms` |
| `scheduler.availability.cache.poll-interval` | `10ms` |

## References

- Nishtala et al., "Scaling Memcache at Facebook", USENIX NSDI 2013, §3.2.1 Leases — wait-and-retry on a held rebuild token.
- AWS Builders' Library, "Caching challenges and strategies" — thundering herd, request coalescing, fail-open.
- redis.io, "Distributed Locks with Redis" — `SET NX PX` + Lua owner-token release (single instance; Redlock not needed).
- Vattani, Chierichetti, Lowenstein, "Optimal Probabilistic Cache Stampede Prevention", VLDB 2015 — alternative (early expiration), not used.
