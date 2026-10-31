package com.keyloop.scheduler.batch.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Daily cleanup settings (Spec04 §7, CLN-01…03). */
@ConfigurationProperties("scheduler.outbox.cleanup")
public record OutboxCleanupProperties(
        boolean enabled,
        String cron,
        Duration processedRetention,
        Duration failedRetention,
        Duration batchMetadataRetention) {
}
