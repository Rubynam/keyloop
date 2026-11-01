package com.keyloop.scheduler.batch.schedule;

import com.keyloop.scheduler.batch.config.outbox.OutboxRelayProperties;
import com.keyloop.scheduler.batch.constant.SchedulingConstants;
import com.keyloop.scheduler.batch.constant.outbox.RelayConstants;
import com.keyloop.scheduler.batch.service.outbox.OutboxRelayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled entry point of the outbox relay. It wakes up every {@code poll-delay} and
 * lets {@link OutboxRelayService} drain due events, so the API cache drops stale slots
 * soon after a booking. No ShedLock: several instances get different rows. Spec04 §4.1.
 *
 * <pre>
 * every 500 ms:  tick() = 100 (full) → tick() = 100 (full) → tick() = 37 → wait
 * </pre>
 */
@Component
@ConditionalOnProperty(name = RelayConstants.RELAY_ENABLED_PROPERTY, havingValue = SchedulingConstants.ENABLED)
public class OutboxRelay {

    private final OutboxRelayService relayService;
    private final OutboxRelayProperties properties;

    public OutboxRelay(OutboxRelayService relayService, OutboxRelayProperties properties) {
        this.relayService = relayService;
        this.properties = properties;
    }

    /**
     * Runs batches until one is not full.
     *
     * <pre>
     * batch-size 100, 250 due  →  100, 100, 50  (three ticks, then wait poll-delay)
     * </pre>
     */
    @Scheduled(fixedDelayString = RelayConstants.RELAY_POLL_DELAY)
    public void relay() {
        // A full batch means more rows are probably due: go on without waiting for the next tick
        while (relayService.tick() == properties.batchSize()) {
            // next batch
        }
    }
}
