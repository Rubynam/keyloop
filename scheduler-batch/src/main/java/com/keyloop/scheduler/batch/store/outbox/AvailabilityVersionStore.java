package com.keyloop.scheduler.batch.store.outbox;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Version numbers of the server-api availability cache, one per dealership day. A bump
 * makes every cached slot list of that day unreachable at once, so readers compute it
 * again from PostgreSQL. Spec04 §4.2, ROADMAP D1, cache-read-path.md.
 *
 * <pre>
 * bump(D1, 2026-11-05)
 *   avail:ver:D1:2026-11-05   3 → 4    (TTL reset to version-key-ttl)
 *   readers: avail:D1:2026-11-05:v3:… no longer read, v4 built on the next miss
 * </pre>
 */
public interface AvailabilityVersionStore {

    /**
     * Adds one to the version of the day and resets its TTL. A missing key starts at 1.
     *
     * <pre>
     * (D1, 2026-11-05)  missing → 1;  3 → 4
     * </pre>
     *
     * @throws org.springframework.dao.DataAccessException Redis is down or timed out (retryable)
     */
    void bump(UUID dealershipId, LocalDate date);
}
