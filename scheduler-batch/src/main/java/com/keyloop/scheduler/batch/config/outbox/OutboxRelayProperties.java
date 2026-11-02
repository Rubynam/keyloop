package com.keyloop.scheduler.batch.config.outbox;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Relay settings (Spec04 §7). Retry policy: one try plus {@code maxRetries}
 * retries, {@code retryDelay} apart, then the event is FAILED. Values are checked
 * at startup, so a bad value stops the app instead of a relay that spins or never
 * waits. Story outbox-relay Task 3.
 *
 * <pre>
 * batch-size: 100    → starts
 * batch-size: 0      → startup fails: batchSize, "must be greater than 0"
 * max-retries: -1    → startup fails: maxRetries
 * retry-delay: 0s    → startup fails: retryDelay
 * poll-delay / version-key-ttl: 0s → startup fails: pollDelay / versionKeyTtl
 * </pre>
 */
@Validated
@ConfigurationProperties("scheduler.outbox.relay")
public record OutboxRelayProperties(
        boolean enabled,
        @NotNull @DurationMin(millis = 1) Duration pollDelay,
        @Positive int batchSize,
        @PositiveOrZero int maxRetries,
        @NotNull @DurationMin(millis = 1) Duration retryDelay,
        @NotNull @DurationMin(millis = 1) Duration versionKeyTtl) {
}
