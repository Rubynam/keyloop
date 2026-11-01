package com.keyloop.scheduler.batch.model.outbox;

import java.util.UUID;

/**
 * One due row of {@code outbox_events}, as the relay reads it.
 *
 * @param payload the JSON payload as text (Spec04 §4.4)
 * @param attempts tries so far; 0 for a new event
 */
public record OutboxEvent(UUID id, String eventType, String payload, int attempts) {}
