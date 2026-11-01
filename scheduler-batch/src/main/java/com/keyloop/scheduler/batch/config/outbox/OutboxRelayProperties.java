package com.keyloop.scheduler.batch.config.outbox;

import java.time.Duration;

import com.keyloop.scheduler.batch.constant.outbox.RelayConstants;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Relay settings (Spec04 §7). Retry policy: one try plus {@code maxRetries}
 * retries, {@code retryDelay} apart, then the event is FAILED. Values are checked
 * at startup, so a bad value stops the app instead of a relay that spins, never
 * waits or overflows. Upper limits live in {@link RelayConstants}.
 * Story outbox-relay Task 3; spec-outbox-properties-hardening.
 *
 * <pre>
 * batch-size: 100    → starts
 * batch-size: 0      → startup fails: batchSize, "must be greater than 0"
 * batch-size: 1001   → startup fails: batchSize            (max 1000)
 * max-retries: -1    → startup fails: maxRetries
 * max-retries: 101   → startup fails: maxRetries           (max 100)
 * batch-size / max-retries missing → startup fails: batchSize / maxRetries
 * retry-delay: 0s    → startup fails: retryDelay
 * poll-delay / version-key-ttl: 0s → startup fails: pollDelay / versionKeyTtl
 * poll-delay, retry-delay &gt; 1h, version-key-ttl &gt; 30d → startup fails, names it
 * </pre>
 */
@Validated
@ConfigurationProperties("scheduler.outbox.relay")
public record OutboxRelayProperties(
        boolean enabled,
        @NotNull @DurationMin(millis = 1) @DurationMax(hours = RelayConstants.MAX_POLL_DELAY_HOURS)
        Duration pollDelay,
        @NotNull @Positive @Max(RelayConstants.MAX_BATCH_SIZE) Integer batchSize,
        @NotNull @PositiveOrZero @Max(RelayConstants.MAX_RETRIES_LIMIT) Integer maxRetries,
        @NotNull @DurationMin(millis = 1) @DurationMax(hours = RelayConstants.MAX_RETRY_DELAY_HOURS)
        Duration retryDelay,
        @NotNull @DurationMin(millis = 1) @DurationMax(days = RelayConstants.MAX_VERSION_KEY_TTL_DAYS)
        Duration versionKeyTtl) {
}
