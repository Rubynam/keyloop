package com.keyloop.scheduler.batch.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Outbox rows inserted the way the booking transaction will write them (Spec04 §4.4 contract samples). */
public final class OutboxRows {

    private static final Path SAMPLES = Path.of("../docs/contracts/outbox");

    /** State of one row as the relay left it. */
    public record State(int attempts, Instant nextAttemptAt, Instant processedAt, Instant failedAt, String lastError) {}

    private final JdbcClient jdbc;

    public OutboxRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The contract sample of this event type, e.g. {@code AppointmentBooked}. */
    public static String sample(String eventType) {
        try {
            return Files.readString(SAMPLES.resolve(eventType + ".json"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public UUID insert(String eventType, String payload) {
        return jdbc.sql("""
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                        VALUES ('Test', :aggregate, :type, :payload::jsonb) RETURNING id
                        """)
                .param("aggregate", UUID.randomUUID().toString())
                .param("type", eventType)
                .param("payload", payload)
                .query(UUID.class)
                .single();
    }

    public State state(UUID id) {
        return jdbc.sql("""
                        SELECT attempts, next_attempt_at, processed_at, failed_at, last_error
                        FROM outbox_events WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new State(rs.getInt("attempts"),
                        instant(rs.getObject("next_attempt_at", OffsetDateTime.class)),
                        instant(rs.getObject("processed_at", OffsetDateTime.class)),
                        instant(rs.getObject("failed_at", OffsetDateTime.class)),
                        rs.getString("last_error")))
                .single();
    }

    /** PostgreSQL wall clock now: the relay's own time source. */
    public Instant dbNow() {
        return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    /** Skips the retry wait: makes the row due now. */
    public void makeDue(UUID id) {
        jdbc.sql("UPDATE outbox_events SET next_attempt_at = now() WHERE id = :id").param("id", id).update();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
