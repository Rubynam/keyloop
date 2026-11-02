package com.keyloop.scheduler.api.constant.lookup;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Fixed values of the availability lookup: window limits, pair cap and error text.
 * Tests depend on this text, so do not change a character.
 *
 * <pre>
 * WINDOW_TOO_LONG.formatted(1500, MAX_WINDOW_MINUTES)  →  "Window is 1500 minutes; at most 1440 are allowed"
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LookupConstants {

    /** Longest window one lookup may ask for; same bound as {@code ck_service_types_duration}. */
    public static final int MAX_WINDOW_MINUTES = 1440;
    /** At most this many (bay, technician) pairs in one body; {@code totalPairs} still has the full count. */
    public static final int MAX_PAIRS = 200;

    public static final String WINDOW_TO_NOT_AFTER_FROM = "'to' (%s) must be after 'from' (%s)";
    public static final String WINDOW_TOO_LONG = "Window is %d minutes; at most %d are allowed";
    public static final String INVALID_TIME_ZONE = "Dealership has an invalid time zone: %s";
}
