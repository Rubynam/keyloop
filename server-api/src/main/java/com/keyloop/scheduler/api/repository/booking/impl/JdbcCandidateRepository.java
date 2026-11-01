package com.keyloop.scheduler.api.repository.booking.impl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.keyloop.scheduler.api.model.AppointmentStatus;
import com.keyloop.scheduler.api.repository.booking.CandidateRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Ordering facts for candidate selection (candidate-selection.md steps 3–4). Fit and
 * overlap reuse {@code AvailabilityRepository}. Plain {@link JdbcClient}, PostgreSQL only.
 */
@Repository
public class JdbcCandidateRepository implements CandidateRepository {

    private final JdbcClient jdbc;

    public JdbcCandidateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Integer> countVehicleTypes(Collection<UUID> bayIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        jdbc.sql("""
                        SELECT service_bay_id, count(*)::int AS types
                        FROM service_bay_vehicle_types
                        WHERE service_bay_id IN (:bayIds)
                        GROUP BY service_bay_id
                        """)
                .param("bayIds", bayIds)
                .query(rs -> {
                    counts.put(rs.getObject("service_bay_id", UUID.class), rs.getInt("types"));
                });
        return counts;
    }

    @Override
    public Map<UUID, Integer> countAppointments(Collection<UUID> technicianIds, Instant from, Instant to) {
        Map<UUID, Integer> counts = new HashMap<>();
        jdbc.sql("""
                        SELECT technician_id, count(*)::int AS jobs
                        FROM appointments
                        WHERE technician_id IN (:technicianIds)
                          AND status <> :cancelled
                          AND start_time >= :from AND start_time < :to
                        GROUP BY technician_id
                        """)
                .param("technicianIds", technicianIds)
                .param("cancelled", AppointmentStatus.CANCELLED.name())
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query(rs -> {
                    counts.put(rs.getObject("technician_id", UUID.class), rs.getInt("jobs"));
                });
        return counts;
    }
}
