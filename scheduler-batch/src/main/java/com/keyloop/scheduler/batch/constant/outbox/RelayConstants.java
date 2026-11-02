package com.keyloop.scheduler.batch.constant.outbox;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Fixed values of the outbox relay: the Redis version key, the payload fields it reads,
 * the invalid-payload reasons and the operator log formats. Operators and tests read
 * this text, so do not change a character. Spec04 §4.4, §4.6.
 *
 * <pre>
 * {"dealershipId":"D1","dates":["2026-11-05"]}
 *   → INCR VERSION_KEY_PREFIX + D1 + KEY_SEPARATOR + 2026-11-05   (avail:ver:D1:2026-11-05)
 * {"dates":[]}
 *   → FAILED, last_error = DEALERSHIP_MISSING
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RelayConstants {

    /** Same key the server-api read path uses (cache-read-path.md). */
    public static final String VERSION_KEY_PREFIX = "avail:ver:";
    public static final String KEY_SEPARATOR = ":";

    public static final String RELAY_ENABLED_PROPERTY = "scheduler.outbox.relay.enabled";
    public static final String RELAY_POLL_DELAY = "${scheduler.outbox.relay.poll-delay}";

    public static final String FIELD_DEALERSHIP_ID = "dealershipId";
    public static final String FIELD_DATES = "dates";
    public static final String FIELD_OLD_DATES = "oldDates";
    public static final String FIELD_NEW_DATES = "newDates";

    public static final String PAYLOAD_NOT_JSON = "payload is not valid JSON";
    public static final String PAYLOAD_NOT_OBJECT = "payload is not a JSON object";
    public static final String DEALERSHIP_MISSING = "dealershipId is missing";
    public static final String DEALERSHIP_NOT_UUID = "dealershipId is not a UUID: %s";
    public static final String FIELD_NOT_ARRAY = "%s is not an array";
    public static final String FIELD_INVALID_DATE = "%s has an invalid date: %s";

    /** {@code last_error} of a Redis failure: exception simple name, then its message. */
    public static final String REDIS_ERROR = "%s: %s";

    /** Spec04 §4.3, a failed try with retries left: try, event id, event type, retry delay, error. */
    public static final String RETRY_SCHEDULED_LOG =
            "Cache invalidation try {} failed for outbox event {} ({}), next try in {}: {}";
    /** Spec04 §4.6, after the last retry. */
    public static final String FAILED_AFTER_RETRIES_LOG = "Cache invalidation failed after {} attempts for outbox event {} ({}, "
            + "dealership {}, dates {}); availability may be stale until cache TTL expiry. Last error: {}";
    /** Spec04 §4.6, bad payload. */
    public static final String INVALID_PAYLOAD_LOG =
            "Outbox event {} ({}) has an invalid payload and was marked FAILED without retry: {}";

    /** {@code last_error} of a row whose tries all started but none finished (the relay stopped during the work). */
    public static final String CLAIM_EXHAUSTED_ERROR = "CLAIM_EXHAUSTED: tries started but never finished";
    /** Logged when the claim marks such a row FAILED: event id, event type, attempts. */
    public static final String CLAIM_EXHAUSTED_LOG = "Outbox event {} ({}) was marked FAILED after {} attempts that did not "
            + "finish (the relay stopped during the work); availability may be stale until cache TTL expiry";
    /** Logged when the work transaction cannot re-lock its claimed row: event id, event type, claimed try. */
    public static final String RELOCK_SKIPPED_LOG = "Outbox event {} ({}) skipped: another relay instance owns try {}";
}
