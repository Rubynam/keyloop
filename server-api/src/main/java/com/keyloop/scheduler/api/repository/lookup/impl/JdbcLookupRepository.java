package com.keyloop.scheduler.api.repository.lookup.impl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.keyloop.scheduler.api.model.AppointmentStatus;
import com.keyloop.scheduler.api.model.lookup.NamedResource;
import com.keyloop.scheduler.api.repository.lookup.LookupRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL for the availability lookup. "Free" is an anti-join on appointments, so
 * one statement per resource kind answers the whole window. Optional filters are
 * {@code CAST(:p AS uuid) IS NULL OR ...}: the cast lets PostgreSQL type a null parameter.
 */
@Repository
public class JdbcLookupRepository implements LookupRepository {

    private final JdbcClient jdbc;

    public JdbcLookupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean bayBelongsTo(UUID serviceBayId, UUID dealershipId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM service_bays WHERE id = :id AND dealership_id = :dealershipId)")
                .param("id", serviceBayId)
                .param("dealershipId", dealershipId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public boolean technicianBelongsTo(UUID technicianId, UUID dealershipId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM technicians WHERE id = :id AND dealership_id = :dealershipId)")
                .param("id", technicianId)
                .param("dealershipId", dealershipId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public List<NamedResource> findFreeBays(UUID dealershipId, @Nullable UUID serviceBayId,
                                            @Nullable UUID vehicleTypeId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT b.id, b.name
                        FROM service_bays b
                        WHERE b.dealership_id = :dealershipId AND b.active
                          AND (CAST(:bayId AS uuid) IS NULL OR b.id = :bayId)
                          AND (CAST(:vehicleTypeId AS uuid) IS NULL OR EXISTS (
                                SELECT 1 FROM service_bay_vehicle_types bv
                                WHERE bv.service_bay_id = b.id AND bv.vehicle_type_id = :vehicleTypeId))
                          AND NOT EXISTS (
                                SELECT 1 FROM appointments a
                                WHERE a.service_bay_id = b.id AND a.status <> :cancelled
                                  AND a.start_time < :to AND a.end_time > :from)
                        ORDER BY b.name, b.id
                        """)
                .param("dealershipId", dealershipId)
                .param("bayId", serviceBayId)
                .param("vehicleTypeId", vehicleTypeId)
                .param("cancelled", AppointmentStatus.CANCELLED.name())
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query((rs, n) -> new NamedResource(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }

    @Override
    public List<NamedResource> findFreeTechnicians(UUID dealershipId, @Nullable UUID technicianId,
                                                   @Nullable UUID serviceTypeId, @Nullable UUID vehicleTypeId,
                                                   Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT t.id, t.name
                        FROM technicians t
                        WHERE t.dealership_id = :dealershipId AND t.active
                          AND (CAST(:technicianId AS uuid) IS NULL OR t.id = :technicianId)
                          AND (CAST(:serviceTypeId AS uuid) IS NULL OR EXISTS (
                                SELECT 1 FROM technician_skills s
                                WHERE s.technician_id = t.id AND s.service_type_id = :serviceTypeId))
                          AND (CAST(:vehicleTypeId AS uuid) IS NULL OR EXISTS (
                                SELECT 1 FROM technician_vehicle_types tv
                                WHERE tv.technician_id = t.id AND tv.vehicle_type_id = :vehicleTypeId))
                          AND NOT EXISTS (
                                SELECT 1 FROM appointments a
                                WHERE a.technician_id = t.id AND a.status <> :cancelled
                                  AND a.start_time < :to AND a.end_time > :from)
                        ORDER BY t.name, t.id
                        """)
                .param("dealershipId", dealershipId)
                .param("technicianId", technicianId)
                .param("serviceTypeId", serviceTypeId)
                .param("vehicleTypeId", vehicleTypeId)
                .param("cancelled", AppointmentStatus.CANCELLED.name())
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query((rs, n) -> new NamedResource(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }
}
