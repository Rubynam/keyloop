package com.keyloop.scheduler.api.constant.availability;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Fixed values of the availability read path: range limits, error text and
 * Redis key prefixes. Tests and the relay depend on this text, so do not change a
 * character. Spec05 §6, cache-read-path.md.
 *
 * <pre>
 * VERSION_KEY_PREFIX + dealer + ":" + date      →  avail:ver:{dealer}:{date}
 * RANGE_TOO_LONG.formatted(15, MAX_DAYS)        →  "Range is 15 days; at most 14 are allowed"
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AvailabilityConstants {

    /** D1 abuse bound: at most this many local dates per request. */
    public static final int MAX_DAYS = 14;
    /** Sane date bounds: java.time extremes would overflow on plusDays / atStartOfDay. */
    public static final int MIN_YEAR = 1;
    public static final int MAX_YEAR = 9999;

    public static final String RANGE_YEARS_OUT_OF_BOUNDS = "Dates must be between years %d and %d";
    public static final String RANGE_TO_BEFORE_FROM = "'to' (%s) is before 'from' (%s)";
    public static final String RANGE_TOO_LONG = "Range is %d days; at most %d are allowed";
    public static final String INVALID_TIME_ZONE = "Dealership has an invalid time zone: %s";
    /** Startup error: {@code scheduler.availability.slot-step} is not a whole-minute divisor of 24h. */
    public static final String INVALID_SLOT_STEP =
            "scheduler.availability.slot-step must be whole minutes, greater than 0 and divide 24h, but was %s";

    /** Name of the event added to the current span when Redis fails and we read PostgreSQL. */
    public static final String FAIL_OPEN_EVENT = "availability.cache.fail-open";
    public static final String FAIL_OPEN_EVENT_DESCRIPTION = "Redis error, read from PostgreSQL";
    public static final String CACHE_NOT_A_SLOT_LIST = "Cached availability is not a slot list: ";
    public static final String CACHE_WAIT_INTERRUPTED = "Interrupted while waiting for the availability cache";

    /** Version of one dealership day, bumped by the relay. */
    public static final String VERSION_KEY_PREFIX = "avail:ver:";
    /** Full-day slot list of one (dealership, date, version, service type, vehicle type). */
    public static final String SLOTS_KEY_PREFIX = "avail:";
    /** Rebuild lock of one slot-list key. */
    public static final String LOCK_KEY_PREFIX = "avail:lock:";
    public static final String KEY_SEPARATOR = ":";
    public static final String KEY_VERSION_MARK = ":v";

    /** Lua script that deletes the lock only when it still holds our token. */
    public static final String RELEASE_LOCK_SCRIPT = "redis/release-lock.lua";

    /** JSON fields of one cached slot. */
    public static final String SLOT_FIELD_START = "start";
    public static final String SLOT_FIELD_END = "end";
}
