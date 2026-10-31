package com.keyloop.scheduler.batch.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Placeholder: drains {@code outbox_events} and bumps the Redis availability
 * version keys (Spec04 §4). A plain scheduled worker, not a Batch job (Spec04 Q1).
 * No ShedLock: {@code FOR UPDATE SKIP LOCKED} already lets several instances
 * share the work.
 */
@Component
@ConditionalOnProperty(name = "scheduler.outbox.relay.enabled", havingValue = "true")
public class OutboxRelay {

    @Scheduled(fixedDelayString = "${scheduler.outbox.relay.poll-delay}")
    public void relay() {
        // TODO(relay spec): one batch per transaction, retry policy of Spec04 §4.2–§4.3
    }
}
