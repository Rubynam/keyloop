package com.keyloop.scheduler.batch.repository.outbox;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.keyloop.scheduler.batch.model.outbox.OutboxEvent;

/**
 * Data access of the outbox relay on {@code outbox_events}. The relay claims one due row
 * in a short transaction (TX A) and counts the try there. Then it locks the row again in
 * a second transaction (TX B) and marks it processed, due again later, or FAILED.
 * All timestamps are PostgreSQL {@code now()}, never the JVM clock. Spec04 §4.2, §4.3, REL-04.
 *
 * <pre>
 * TX A  lockNextDue()             → E1 (attempts 0)      (other instances skip E1)
 *       countTry(E1, 2s)          → 1                    COMMIT: try counted
 * TX B  relock(E1, 1)             → true                 (row locked until COMMIT)
 *       markProcessed(E1)                                COMMIT
 * </pre>
 */
public interface OutboxEventRepository {

    /**
     * The oldest due, unfinished event, locked with {@code FOR UPDATE SKIP LOCKED} until
     * the transaction ends. Rows locked by another instance are skipped.
     *
     * <pre>
     * due: E1 (created 10:00), E2 (created 10:01)  →  E1;   none due  →  empty
     * </pre>
     */
    Optional<OutboxEvent> lockNextDue();

    /**
     * Counts one try and keeps other instances away for {@code delay} (the lease). Call it
     * on a row locked by {@link #lockNextDue()}.
     *
     * <pre>
     * countTry(E1, 2s)  →  attempts 0 → 1, next_attempt_at = now() + 2s;  returns 1
     * </pre>
     *
     * @return the new {@code attempts}: the claimed try number
     */
    int countTry(UUID id, Duration delay);

    /**
     * Locks the claimed row again at the start of the work transaction, with
     * {@code FOR UPDATE}. It waits when another transaction holds the row: a claim scan
     * of another instance can keep a lock on a row it did not take. It only succeeds
     * while the row is still ours: same try, not processed, not failed.
     *
     * <pre>
     * relock(E1, 1)  attempts 1, PENDING            →  true  (locked until COMMIT)
     * relock(E1, 1)  locked by another TX A         →  waits, then true
     * relock(E1, 1)  attempts 2, or PROCESSED/FAILED →  false
     * </pre>
     */
    boolean relock(UUID id, int claimedAttempts);

    /**
     * Sets {@code processed_at = now()}.
     *
     * <pre>
     * PENDING ──markProcessed──► PROCESSED
     * </pre>
     */
    void markProcessed(UUID id);

    /**
     * Stores the error and makes the event due again after {@code delay}. It does not
     * change {@code attempts}: the claim already counted the try.
     *
     * <pre>
     * scheduleRetry(E2, 2s, err)  →  next_attempt_at = now() + 2s, last_error = err
     * </pre>
     */
    void scheduleRetry(UUID id, Duration delay, String error);

    /**
     * Ends the event as FAILED: {@code failed_at = now()} and the error stored.
     * {@code attempts} stays as the claim left it.
     *
     * <pre>
     * PENDING ──markFailed(E2, err)──► FAILED
     * </pre>
     */
    void markFailed(UUID id, String error);
}
