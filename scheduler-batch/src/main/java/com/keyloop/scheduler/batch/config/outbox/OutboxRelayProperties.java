package com.keyloop.scheduler.batch.config.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Relay settings (Spec04 §7). Retry policy: one try plus {@code maxRetries}
 * retries, {@code retryDelay} apart, then the event is FAILED.
 */
@ConfigurationProperties("scheduler.outbox.relay")
public record OutboxRelayProperties(
        boolean enabled,
        Duration pollDelay,
        int batchSize,
        int maxRetries,
        Duration retryDelay,
        Duration versionKeyTtl) {
}
