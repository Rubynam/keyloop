package com.keyloop.scheduler.batch.job.outbox;

import com.keyloop.scheduler.batch.constant.SchedulingConstants;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Placeholder: daily Batch job (Spec04 CLN-01…03) that deletes PROCESSED and
 * FAILED outbox rows past their retention, then prunes old Batch metadata.
 */
@Configuration
@ConditionalOnProperty(name = SchedulingConstants.OUTBOX_CLEANUP_ENABLED_PROPERTY, havingValue = SchedulingConstants.ENABLED)
public class OutboxCleanupJobConfig {
    // TODO(cleanup spec): chunked delete steps + daily launch from JobScheduler
}
