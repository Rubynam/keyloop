package com.keyloop.scheduler.batch.repository.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.keyloop.scheduler.batch.model.outbox.OutboxEvent;

/**
 * Data access of the outbox relay on {@code outbox_events}. The relay takes due rows
 * under a row lock and then marks each one processed, due again later, or FAILED.
 * Call every method inside the relay's transaction. All timestamps are PostgreSQL
 * {@code now()}, never the JVM clock. Spec04 §4.2, §4.3, REL-04.
 *
 * <pre>
 * BEGIN
 *   lockDueBatch(100)            → [E1, E2]     (other instances skip E1, E2)
 *   markProcessed(E1)
 *   scheduleRetry(E2, 1, 2s, "RedisConnectionFailureException: ...")
 * COMMIT
 * </pre>
 */
public interface OutboxEventRepository {

    /**
     * Due, unfinished events, oldest first, locked with {@code FOR UPDATE SKIP LOCKED}
     * until the transaction ends. Rows locked by another instance are skipped.
     *
     * <pre>
     * lockDueBatch(2)  →  [E1 (created 10:00), E2 (created 10:01)]
     * </pre>
     */
    List<OutboxEvent> lockDueBatch(int batchSize);

    /**
     * Sets {@code processed_at = now()}.
     *
     * <pre>
     * PENDING ──markProcessed──► PROCESSED
     * </pre>
     */
    void markProcessed(UUID id);

    /**
     * Stores the try count and error and makes the event due again after {@code delay}.
     *
     * <pre>
     * scheduleRetry(E2, 1, 2s, err)  →  attempts = 1, next_attempt_at = now() + 2s, last_error = err
     * </pre>
     */
    void scheduleRetry(UUID id, int attempts, Duration delay, String error);

    /**
     * Ends the event as FAILED: {@code failed_at = now()}, try count and error stored.
     *
     * <pre>
     * PENDING ──markFailed(E2, 4, err)──► FAILED
     * </pre>
     */
    void markFailed(UUID id, int attempts, String error);
}
