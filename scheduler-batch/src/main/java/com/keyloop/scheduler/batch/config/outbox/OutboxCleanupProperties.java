package com.keyloop.scheduler.batch.config.outbox;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Daily cleanup settings. Values are checked at startup, even when {@code enabled}
 * is false, so a zero retention can never delete rows at once (for example FAILED
 * rows an operator still needs to read). {@code cron} is only checked for not blank;
 * its syntax is not checked here.
 * Spec04 §7, CLN-01…03; spec-outbox-properties-hardening.
 *
 * <pre>
 * cron: 0 0 3 * * *, retentions 7d / 30d / 7d → starts
 * cron: ""  (or missing)                       → startup fails: cron
 * failed-retention: 0s (or missing)            → startup fails: failedRetention
 * enabled: false, failed-retention: 0s         → startup fails: failedRetention
 * </pre>
 */
@Validated
@ConfigurationProperties("scheduler.outbox.cleanup")
public record OutboxCleanupProperties(
        boolean enabled,
        @NotBlank String cron,
        @NotNull @DurationMin(millis = 1) Duration processedRetention,
        @NotNull @DurationMin(millis = 1) Duration failedRetention,
        @NotNull @DurationMin(millis = 1) Duration batchMetadataRetention) {
}
