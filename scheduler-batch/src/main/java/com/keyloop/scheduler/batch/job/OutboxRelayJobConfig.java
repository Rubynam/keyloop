package com.keyloop.scheduler.batch.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Placeholder: drains {@code outbox_events} and bumps the Redis availability
 * version keys (ROADMAP D1). Whether this stays a Batch job or becomes a plain
 * polling worker is decided in the outbox spec.
 */
@Configuration
@ConditionalOnProperty(name = "scheduler.jobs.outbox-relay.enabled", havingValue = "true")
public class OutboxRelayJobConfig {
    // TODO(outbox spec): job definition + schedule
}
