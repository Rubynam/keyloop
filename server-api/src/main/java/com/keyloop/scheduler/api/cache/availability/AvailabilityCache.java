package com.keyloop.scheduler.api.cache.availability;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse.Slot;

/**
 * Read-side cache of full-day slot lists for the availability query. It saves
 * PostgreSQL work when many customers look at the same day. PostgreSQL still decides
 * every booking, so a stale or missing entry costs speed, never correctness. A Redis
 * error never fails a read: the request reads PostgreSQL instead (fail-open).
 * spec-availability-ops, cache-read-path.md, ROADMAP D1.
 *
 * <pre>
 * Lookup l = cache.lookup(D1, OIL, SUV, [11-02, 11-03]);   step 1: read versions
 * ... PostgreSQL checks (404s) ...
 * l.slots(missing -&gt; computeFromDb(missing));              step 2: hits + rebuild of misses
 *
 *   11-02  hit  (v4) ──────────────► from Redis
 *   11-03  miss (v0) ──loader([11-03])──► from PostgreSQL, then written to Redis
 * </pre>
 */
public interface AvailabilityCache {

    /**
     * Step 1 of the read: reads the version of every date. Call it before any
     * PostgreSQL read of the same request, so a bump that lands while we compute
     * makes our entry unreachable instead of stale.
     *
     * <pre>
     * lookup(D1, OIL, SUV, [11-02])  →  Lookup { 11-02 → v4 }
     * </pre>
     */
    Lookup lookup(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId, List<LocalDate> dates);

    /**
     * One request's cache read: versions first (from {@link #lookup}), then
     * {@link #slots} with a PostgreSQL loader for the dates that are not cached.
     *
     * <pre>
     * slots(loader)  →  { 11-02 → [09:00-10:00, ...], 11-03 → [] }
     * </pre>
     */
    interface Lookup {

        /**
         * Full-day slot lists (no now-cut) for every date of the lookup. Dates that are
         * not cached are computed by {@code loader}; its errors are not caught.
         *
         * <pre>
         * dates [11-02, 11-03], cached 11-02  →  loader([11-03]) is called once
         * </pre>
         */
        Map<LocalDate, List<Slot>> slots(Function<Set<LocalDate>, Map<LocalDate, List<Slot>>> loader);
    }
}
