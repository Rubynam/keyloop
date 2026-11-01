package com.keyloop.scheduler.batch.repository.outbox.impl;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.keyloop.scheduler.batch.model.outbox.OutboxEvent;
import com.keyloop.scheduler.batch.repository.outbox.OutboxEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link OutboxEventRepository} on PostgreSQL with {@link JdbcClient}. The due scan uses
 * the partial index {@code idx_outbox_events_pending} (Spec04 §5).
 */
@Repository
public class JdbcOutboxEventRepository implements OutboxEventRepository {

    private final JdbcClient jdbc;

    public JdbcOutboxEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OutboxEvent> lockDueBatch(int batchSize) {
        return jdbc.sql("""
                        SELECT id, event_type, payload::text AS payload, attempts
                        FROM outbox_events
                        WHERE processed_at IS NULL AND failed_at IS NULL
                          AND next_attempt_at <= now()
                        ORDER BY created_at, id
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("batchSize", batchSize)
                .query((rs, n) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("payload"), rs.getInt("attempts")))
                .list();
    }

    @Override
    public void markProcessed(UUID id) {
        jdbc.sql("UPDATE outbox_events SET processed_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    @Override
    public void scheduleRetry(UUID id, int attempts, Duration delay, String error) {
        jdbc.sql("""
                        UPDATE outbox_events
                        SET attempts = :attempts,
                            next_attempt_at = now() + :delayMs * interval '1 millisecond',
                            last_error = :error
                        WHERE id = :id
                        """)
                .param("attempts", attempts)
                .param("delayMs", delay.toMillis())
                .param("error", error)
                .param("id", id)
                .update();
    }

    @Override
    public void markFailed(UUID id, int attempts, String error) {
        jdbc.sql("""
                        UPDATE outbox_events
                        SET attempts = :attempts, failed_at = now(), last_error = :error
                        WHERE id = :id
                        """)
                .param("attempts", attempts)
                .param("error", error)
                .param("id", id)
                .update();
    }
}
