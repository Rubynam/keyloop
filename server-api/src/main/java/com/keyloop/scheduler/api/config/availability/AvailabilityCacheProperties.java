package com.keyloop.scheduler.api.config.availability;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Timings of the availability cache (spec-availability-ops, cache-read-path.md). All of
 * them are configuration, not code constants.
 *
 * @param ttl          base lifetime of one cached day
 * @param ttlPadding   random extra lifetime, 0 up to this value, added to {@code ttl}
 * @param lockTtl      how long a rebuild lock lives if its owner never releases it
 * @param lockWait     how long a request waits for another request's rebuild
 * @param pollInterval how often a waiting request looks at the cache again
 */
@ConfigurationProperties("scheduler.availability.cache")
public record AvailabilityCacheProperties(
        boolean enabled,
        Duration ttl,
        Duration ttlPadding,
        Duration lockTtl,
        Duration lockWait,
        Duration pollInterval) {
}
