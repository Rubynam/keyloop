package com.keyloop.scheduler.api.repository.appointment.impl;

import com.keyloop.scheduler.api.repository.appointment.OutboxRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox insert (Spec04 §4, Spec07 §7.2), in the caller's transaction. Same row shape as
 * the load-test simulator. The payload is serialized with the app {@link JsonMapper}, so
 * local dates are written as {@code yyyy-MM-dd} text.
 *
 * <pre>
 * append("Appointment", "A1", "AppointmentBooked", payload)  →  outbox_events row:
 *   aggregate_type = Appointment, aggregate_id = A1, event_type = AppointmentBooked,
 *   payload = {"appointmentId": "A1", "dealershipId": "D1", "dates": ["2026-11-02"]}
 * </pre>
 */
@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcOutboxRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void append(String aggregateType, String aggregateId, String eventType, Object payload) {
        jdbc.sql("""
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                        VALUES (:aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))
                        """)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }
}
