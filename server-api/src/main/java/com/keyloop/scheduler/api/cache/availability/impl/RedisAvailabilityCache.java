package com.keyloop.scheduler.api.cache.availability.impl;

import static com.keyloop.scheduler.api.constant.availability.AvailabilityConstants.CACHE_NOT_A_SLOT_LIST;
import static com.keyloop.scheduler.api.constant.availability.AvailabilityConstants.CACHE_WAIT_INTERRUPTED;
import static com.keyloop.scheduler.api.constant.availability.AvailabilityConstants.FAIL_OPEN_EVENT;
import static com.keyloop.scheduler.api.constant.availability.AvailabilityConstants.FAIL_OPEN_EVENT_DESCRIPTION;
import static com.keyloop.scheduler.api.constant.availability.AvailabilityConstants.KEY_SEPARATOR;
import static com.keyloop.scheduler.api.constant.availability.AvailabilityConstants.KEY_VERSION_MARK;
import static com.keyloop.scheduler.api.constant.availability.AvailabilityConstants.LOCK_KEY_PREFIX;
import static com.keyloop.scheduler.api.constant.availability.AvailabilityConstants.SLOTS_KEY_PREFIX;
import static com.keyloop.scheduler.api.constant.availability.AvailabilityConstants.SLOT_FIELD_END;
import static com.keyloop.scheduler.api.constant.availability.AvailabilityConstants.SLOT_FIELD_START;
import static com.keyloop.scheduler.api.constant.availability.AvailabilityConstants.VERSION_KEY_PREFIX;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

import com.keyloop.scheduler.api.cache.availability.AvailabilityCache;
import com.keyloop.scheduler.api.config.availability.AvailabilityCacheProperties;
import com.keyloop.scheduler.api.constant.availability.AvailabilityConstants;
import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse.Slot;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis implementation of {@link AvailabilityCache}: versioned cache-aside for the full
 * slot list of one local date (spec-availability-ops, cache-read-path.md, ROADMAP D1).
 * Redis is only a read-side copy: PostgreSQL decides every booking, so a stale or
 * missing entry costs speed, never correctness. Prefixes live in {@link AvailabilityConstants}.
 *
 * <h2>Keys</h2>
 * <pre>
 * avail:ver:{dealerId}:{date}                                         version, bumped by the relay
 * avail:{dealerId}:{date}:v{ver}:{serviceTypeId}:{vehicleTypeId}      JSON slot list of the full day
 * avail:lock:{dealerId}:{date}:v{ver}:{serviceTypeId}:{vehicleTypeId} rebuild lock, owner token
 * </pre>
 *
 * <h2>Version key</h2>
 * One booking changes every service type and vehicle type of that dealership day, so
 * deleting each slot-list key would never be complete. Instead the relay bumps one
 * number per (dealership, date), and every old key becomes unreachable at once.
 * Example: {@code avail:ver:hub:2026-11-05} goes 3 → 4, so reads move from
 * {@code …:v3:…} to {@code …:v4:…} and the {@code v3} keys expire unused. A missing
 * version reads as 0. The version is read <em>before</em> PostgreSQL: if a bump lands
 * while we compute, our entry is stored under the old version and is never read again.
 *
 * <h2>TTL padding</h2>
 * Keys built at the same moment (for example right after a version bump) would all
 * expire in the same second and hit PostgreSQL together. A random padding spreads the
 * expiry. Example with TTL 30 s and padding 0–30 s: three keys built at 10:00:00
 * expire at 10:00:41, 10:00:33 and 10:00:57 instead of all at 10:00:30. The slot-list
 * TTL (at most 60 s) stays far below the version-key TTL (2 days), so when an expired
 * version reads as 0 again, every old {@code :v0:} entry is long gone.
 *
 * <h2>Lock (request coalescing)</h2>
 * The slot list does not depend on who asks, so every user of one key sees the same
 * data. When the key is missing, one request computes it and the others wait and reuse
 * it. The lock protects the database from duplicate work, not the data.
 * <pre>
 * Without lock — key just expired, 50 users ask at once:
 *   user 1..50 → miss → 50 identical DB queries, same result
 * With lock:
 *   user 1     → miss → takes lock → queries DB → writes cache
 *   user 2..50 → miss → sees lock → waits ≤ 100 ms → reads cache
 *   → the DB gets 1 query
 * </pre>
 * A waiter that is still without data after {@code lock-wait} computes from PostgreSQL
 * itself and does not write the cache: it never rejects the request. The lock key holds
 * the version, so a lock for {@code v3} cannot block the rebuild of {@code v4}.
 *
 * <h2>Lua release</h2>
 * The lock can expire while its owner is still slow, and then another request can take
 * it. A plain {@code DEL} would remove that other request's lock. So each owner writes
 * a random token, and the release script checks the token and deletes in one step:
 * Redis runs a script as a whole, so nothing can happen between the check and the delete.
 * Example: A takes the lock (token a1), A is slow, the lock expires, B takes it (token b7);
 * when A finishes, the script sees b7 ≠ a1 and leaves B's lock alone.
 *
 * <h2>Redis down</h2>
 * Any Redis error makes the rest of the request read PostgreSQL (fail-open), with one
 * WARN log line and an event on the current observation (span). It never fails a read.
 */
@Component
public class RedisAvailabilityCache implements AvailabilityCache {

    private static final Logger log = LoggerFactory.getLogger(RedisAvailabilityCache.class);

    private final StringRedisTemplate redis;
    private final RedisScript<Long> releaseScript;
    private final JsonMapper json;
    private final AvailabilityCacheProperties properties;
    private final ObservationRegistry observations;

    public RedisAvailabilityCache(StringRedisTemplate redis, RedisScript<Long> availabilityLockReleaseScript,
                                  JsonMapper json, AvailabilityCacheProperties properties,
                                  ObservationRegistry observations) {
        this.redis = redis;
        this.releaseScript = availabilityLockReleaseScript;
        this.json = json;
        this.properties = properties;
        this.observations = observations;
    }

    static String versionKey(UUID dealershipId, LocalDate date) {
        return VERSION_KEY_PREFIX + dealershipId + KEY_SEPARATOR + date;
    }

    static String slotsKey(UUID dealershipId, LocalDate date, long version, UUID serviceTypeId, UUID vehicleTypeId) {
        return SLOTS_KEY_PREFIX + dealershipId + KEY_SEPARATOR + date + KEY_VERSION_MARK + version
                + KEY_SEPARATOR + serviceTypeId + KEY_SEPARATOR + vehicleTypeId;
    }

    static String lockKey(UUID dealershipId, LocalDate date, long version, UUID serviceTypeId, UUID vehicleTypeId) {
        return LOCK_KEY_PREFIX + dealershipId + KEY_SEPARATOR + date + KEY_VERSION_MARK + version
                + KEY_SEPARATOR + serviceTypeId + KEY_SEPARATOR + vehicleTypeId;
    }

    @Override
    public Lookup lookup(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId, List<LocalDate> dates) {
        RedisLookup lookup = new RedisLookup(dealershipId, serviceTypeId, vehicleTypeId, dates);
        if (properties.enabled()) {
            lookup.readVersions();
        } else {
            lookup.redisOff = true;
        }
        return lookup;
    }

    /** One request's cache read: versions first, then {@link #slots} with a PostgreSQL loader. */
    private final class RedisLookup implements Lookup {

        private final UUID dealershipId;
        private final UUID serviceTypeId;
        private final UUID vehicleTypeId;
        private final List<LocalDate> dates;
        private final Map<LocalDate, Long> versions = new HashMap<>();
        /** Set after the first Redis error: the rest of the request does not try Redis again. */
        private boolean redisOff;

        private RedisLookup(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId, List<LocalDate> dates) {
            this.dealershipId = dealershipId;
            this.serviceTypeId = serviceTypeId;
            this.vehicleTypeId = vehicleTypeId;
            this.dates = List.copyOf(dates);
        }

        private void readVersions() {
            List<String> raw = redisCall(() -> redis.opsForValue()
                    .multiGet(dates.stream().map(d -> versionKey(dealershipId, d)).toList()));
            if (raw == null) {
                return;
            }
            for (int i = 0; i < dates.size(); i++) {
                String value = raw.get(i);
                versions.put(dates.get(i), value == null ? 0L : Long.parseLong(value));
            }
        }

        @Override
        public Map<LocalDate, List<Slot>> slots(Function<Set<LocalDate>, Map<LocalDate, List<Slot>>> loader) {
            Map<LocalDate, List<Slot>> result = new HashMap<>();
            Set<LocalDate> missing = new LinkedHashSet<>(dates);

            // 2. Cache hits
            readCached(missing, result);

            // 3. Take the lock of each missing date, or wait for its owner
            Map<LocalDate, String> owned = new LinkedHashMap<>();
            for (LocalDate date : missing) {
                if (redisOff) {
                    break;
                }
                String token = UUID.randomUUID().toString();
                Boolean acquired = redisCall(() -> redis.opsForValue()
                        .setIfAbsent(lockKey(date), token, properties.lockTtl()));
                if (Boolean.TRUE.equals(acquired)) {
                    owned.put(date, token);
                }
            }
            long waitUntil = System.nanoTime() + properties.lockWait().toNanos();
            try {
                // Another request may have written the value between our miss and our lock
                Set<LocalDate> toBuild = new LinkedHashSet<>(owned.keySet());
                readCached(toBuild, result);
                if (!toBuild.isEmpty()) {
                    Map<LocalDate, List<Slot>> built = loader.apply(toBuild);
                    result.putAll(built);
                    toBuild.forEach(date -> write(date, built.get(date)));
                }
            } finally {
                owned.forEach(this::release);
            }
            missing.removeAll(owned.keySet());

            // Locked by another request: poll the cache until data or lock-wait
            while (!missing.isEmpty() && !redisOff && System.nanoTime() < waitUntil) {
                sleep(properties.pollInterval());
                readCached(missing, result);
            }
            // Timed out or Redis down: compute ourselves and do NOT write the cache
            if (!missing.isEmpty()) {
                result.putAll(loader.apply(missing));
            }
            return result;
        }

        /** Moves every date found in Redis from {@code missing} into {@code result}. */
        private void readCached(Set<LocalDate> missing, Map<LocalDate, List<Slot>> result) {
            if (missing.isEmpty() || redisOff) {
                return;
            }
            List<LocalDate> asked = List.copyOf(missing);
            List<String> values = redisCall(() -> redis.opsForValue().multiGet(asked.stream().map(this::slotsKey).toList()));
            if (values == null) {
                return;
            }
            for (int i = 0; i < asked.size(); i++) {
                String value = values.get(i);
                if (value != null) {
                    result.put(asked.get(i), parse(value));
                    missing.remove(asked.get(i));
                }
            }
        }

        private void write(LocalDate date, List<Slot> slots) {
            if (redisOff) {
                return;
            }
            Duration ttl = properties.ttl().plusMillis(
                    ThreadLocalRandom.current().nextLong(properties.ttlPadding().toMillis() + 1));
            String value = json.writeValueAsString(slots);
            redisCall(() -> {
                redis.opsForValue().set(slotsKey(date), value, ttl);
                return null;
            });
        }

        private void release(LocalDate date, String token) {
            if (redisOff) {
                // The lock expires by itself after lock-ttl
                return;
            }
            redisCall(() -> redis.execute(releaseScript, List.of(lockKey(date)), token));
        }

        private String slotsKey(LocalDate date) {
            return RedisAvailabilityCache.slotsKey(dealershipId, date, versions.get(date), serviceTypeId, vehicleTypeId);
        }

        private String lockKey(LocalDate date) {
            return RedisAvailabilityCache.lockKey(dealershipId, date, versions.get(date), serviceTypeId, vehicleTypeId);
        }

        /** Runs one Redis command; on a Redis error turns the cache off for this request and returns null. */
        private <T> T redisCall(Supplier<T> command) {
            try {
                return command.get();
            } catch (DataAccessException e) {
                redisOff = true;
                failOpen(e);
                return null;
            }
        }
    }

    private List<Slot> parse(String value) {
        try {
            List<Slot> slots = new ArrayList<>();
            // Parsed by hand: a JSON date mapper would move the offset to UTC
            for (JsonNode slot : json.readTree(value)) {
                slots.add(new Slot(OffsetDateTime.parse(slot.required(SLOT_FIELD_START).asString()),
                        OffsetDateTime.parse(slot.required(SLOT_FIELD_END).asString())));
            }
            return List.copyOf(slots);
        } catch (RuntimeException e) {
            // Only we write these keys, so this is a bug, not bad user input
            throw new IllegalStateException(CACHE_NOT_A_SLOT_LIST + value, e);
        }
    }

    private void failOpen(DataAccessException e) {
        log.warn("Availability cache unavailable, reading from PostgreSQL instead: {}", e.getMessage());
        Observation current = observations.getCurrentObservation();
        if (current != null) {
            current.event(Observation.Event.of(FAIL_OPEN_EVENT, FAIL_OPEN_EVENT_DESCRIPTION));
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(CACHE_WAIT_INTERRUPTED, e);
        }
    }
}
