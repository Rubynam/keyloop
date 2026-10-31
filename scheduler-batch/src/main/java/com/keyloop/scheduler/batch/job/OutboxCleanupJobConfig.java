package com.keyloop.scheduler.batch.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/** Placeholder: deletes processed outbox rows older than the retention window (ROADMAP D1). */
@Configuration
@ConditionalOnProperty(name = "scheduler.jobs.outbox-cleanup.enabled", havingValue = "true")
public class OutboxCleanupJobConfig {
    // TODO(outbox spec): job definition + daily schedule
}
