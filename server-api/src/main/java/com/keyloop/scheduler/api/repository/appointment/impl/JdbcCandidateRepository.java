package com.keyloop.scheduler.api.repository.appointment.impl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.keyloop.scheduler.api.model.AppointmentStatus;
import com.keyloop.scheduler.api.model.appointment.BookableBay;
import com.keyloop.scheduler.api.repository.appointment.CandidateRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Facts for candidate selection: the chosen bay and technician load (candidate-selection.md).
 * Technician fit and overlap reuse {@code AvailabilityRepository}. Plain {@link JdbcClient},
 * PostgreSQL only.
 */
@Repository
public class JdbcCandidateRepository implements CandidateRepository {

    private final JdbcClient jdbc;

    public JdbcCandidateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BookableBay> findBookableBay(UUID dealershipId, UUID serviceBayId, UUID vehicleTypeId) {
        return jdbc.sql("""
                        SELECT b.id, b.active,
                               EXISTS (SELECT 1 FROM service_bay_vehicle_types bv
                                       WHERE bv.service_bay_id = b.id
                                         AND bv.vehicle_type_id = :vehicleTypeId) AS takes_vehicle_type
                        FROM service_bays b
                        WHERE b.id = :serviceBayId AND b.dealership_id = :dealershipId
                        """)
                .param("dealershipId", dealershipId)
                .param("serviceBayId", serviceBayId)
                .param("vehicleTypeId", vehicleTypeId)
                .query((rs, n) -> new BookableBay(
                        rs.getObject("id", UUID.class),
                        rs.getBoolean("active"),
                        rs.getBoolean("takes_vehicle_type")))
                .optional();
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
