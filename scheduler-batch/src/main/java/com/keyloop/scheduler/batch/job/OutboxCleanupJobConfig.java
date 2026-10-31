package com.keyloop.scheduler.batch.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Placeholder: daily Batch job (Spec04 CLN-01…03) that deletes PROCESSED and
 * FAILED outbox rows past their retention, then prunes old Batch metadata.
 */
@Configuration
@ConditionalOnProperty(name = "scheduler.outbox.cleanup.enabled", havingValue = "true")
public class OutboxCleanupJobConfig {
    // TODO(cleanup spec): chunked delete steps + daily launch from JobScheduler
}
