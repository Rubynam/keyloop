package com.keyloop.scheduler.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.keyloop.scheduler.api.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaMigrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesEveryMigration() {
        String latest = jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(latest).isEqualTo("4");
    }

    @Test
    void newOutboxEventIsDueAtOnce() {
        UUID id = insertEvent();

        Boolean due = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at <= now() AND attempts = 0 AND failed_at IS NULL"
                        + " AND uuid_extract_version(id) = 7 FROM outbox_events WHERE id = ?",
                Boolean.class, id);
        assertThat(due).isTrue();
    }

    @Test
    void outboxEventCannotBeProcessedAndFailed() {
        UUID id = insertEvent();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE outbox_events SET processed_at = now(), failed_at = now() WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbox_events_terminal");
    }

    private UUID insertEvent() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                VALUES ('Appointment', 'a-1', 'AppointmentBooked',
                        '{"dealershipId": "0194244f-d800-76ca-b4eb-252b0f21ddb6", "dates": []}')
                RETURNING id""", UUID.class);
    }
}
