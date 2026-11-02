package com.keyloop.scheduler.batch.repository.outbox.impl;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.keyloop.scheduler.batch.model.outbox.OutboxEvent;
import com.keyloop.scheduler.batch.repository.outbox.OutboxEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link OutboxEventRepository} on PostgreSQL with {@link JdbcClient}. The due scan uses
 * the partial index {@code idx_outbox_events_pending}; the re-lock reads by primary key
 * (Spec04 §5).
 *
 * <pre>
 * lockNextDue → countTry → COMMIT → relock → markProcessed | scheduleRetry | markFailed → COMMIT
 * </pre>
 */
@Repository
public class JdbcOutboxEventRepository implements OutboxEventRepository {

    private final JdbcClient jdbc;

    public JdbcOutboxEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OutboxEvent> lockNextDue() {
        return jdbc.sql("""
                        SELECT id, event_type, payload::text AS payload, attempts
                        FROM outbox_events
                        WHERE processed_at IS NULL AND failed_at IS NULL
                          AND next_attempt_at <= now()
                        ORDER BY created_at, id
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """)
                .query((rs, n) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("payload"), rs.getInt("attempts")))
                .optional();
    }

    @Override
    public int countTry(UUID id, Duration delay) {
        return jdbc.sql("""
                        UPDATE outbox_events
                        SET attempts = attempts + 1,
                            next_attempt_at = now() + :delayMs * interval '1 millisecond'
                        WHERE id = :id
                        RETURNING attempts
                        """)
                .param("delayMs", delay.toMillis())
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    @Override
    public boolean relock(UUID id, int claimedAttempts) {
        // No SKIP LOCKED: another instance's lockNextDue can keep a lock on this row after
        // rejecting it (stale snapshot, re-check failed) until its TX A ends. Wait for that;
        // the attempts/state check below is what decides ownership.
        return jdbc.sql("""
                        SELECT id
                        FROM outbox_events
                        WHERE id = :id AND attempts = :claimedAttempts
                          AND processed_at IS NULL AND failed_at IS NULL
                        FOR UPDATE
                        """)
                .param("id", id)
                .param("claimedAttempts", claimedAttempts)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    @Override
    public void markProcessed(UUID id) {
        jdbc.sql("UPDATE outbox_events SET processed_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    @Override
    public void scheduleRetry(UUID id, Duration delay, String error) {
        jdbc.sql("""
                        UPDATE outbox_events
                        SET next_attempt_at = now() + :delayMs * interval '1 millisecond',
                            last_error = :error
                        WHERE id = :id
                        """)
                .param("delayMs", delay.toMillis())
                .param("error", error)
                .param("id", id)
                .update();
    }

    @Override
    public void markFailed(UUID id, String error) {
        jdbc.sql("""
                        UPDATE outbox_events
                        SET failed_at = now(), last_error = :error
                        WHERE id = :id
                        """)
                .param("error", error)
                .param("id", id)
                .update();
    }
}
