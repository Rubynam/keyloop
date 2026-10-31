package com.keyloop.scheduler.api.availability;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.keyloop.scheduler.api.domain.AppointmentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL for the availability query (spec-read-availability, DLD §8). Plain
 * {@link JdbcClient}: there are no JPA entities, and none are added for a read.
 */
@Repository
public class AvailabilityRepository {

    /** Business hours as minutes after local midnight; {@code closeMinute} may be 1440 ('24:00'). */
    public record Dealership(String timezone, int openMinute, int closeMinute) {}

    /** One non-cancelled appointment holding a bay and a technician. */
    public record Booking(UUID serviceBayId, UUID technicianId, Instant start, Instant end) {}

    private final JdbcClient jdbc;

    public AvailabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Dealership> findDealership(UUID id) {
        // EXTRACT keeps '24:00' as 1440; a LocalTime cannot hold it. Seconds round inwards
        // (open up, close down) so a slot never starts before open or ends after close.
        return jdbc.sql("""
                        SELECT timezone,
                               ceil(EXTRACT(EPOCH FROM open_time) / 60)::int   AS open_minute,
                               floor(EXTRACT(EPOCH FROM close_time) / 60)::int AS close_minute
                        FROM dealerships WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new Dealership(
                        rs.getString("timezone"), rs.getInt("open_minute"), rs.getInt("close_minute")))
                .optional();
    }

    public Optional<Integer> findServiceDurationMinutes(UUID serviceTypeId) {
        return jdbc.sql("SELECT duration_minutes FROM service_types WHERE id = :id")
                .param("id", serviceTypeId)
                .query(Integer.class)
                .optional();
    }

    public boolean vehicleTypeExists(UUID vehicleTypeId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM vehicle_types WHERE id = :id)")
                .param("id", vehicleTypeId)
                .query(Boolean.class)
                .single();
    }

    /** Active bays of the dealership that take the vehicle type. */
    public List<UUID> findFittingBayIds(UUID dealershipId, UUID vehicleTypeId) {
        return jdbc.sql("""
                        SELECT b.id
                        FROM service_bays b
                        JOIN service_bay_vehicle_types bv ON bv.service_bay_id = b.id
                        WHERE b.dealership_id = :dealershipId AND b.active
                          AND bv.vehicle_type_id = :vehicleTypeId
                        """)
                .param("dealershipId", dealershipId)
                .param("vehicleTypeId", vehicleTypeId)
                .query(UUID.class)
                .list();
    }

    /** Active technicians of the dealership with the skill AND the vehicle-type certification. */
    public List<UUID> findQualifiedTechnicianIds(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId) {
        return jdbc.sql("""
                        SELECT t.id
                        FROM technicians t
                        JOIN technician_skills s ON s.technician_id = t.id
                        JOIN technician_vehicle_types tv ON tv.technician_id = t.id
                        WHERE t.dealership_id = :dealershipId AND t.active
                          AND s.service_type_id = :serviceTypeId
                          AND tv.vehicle_type_id = :vehicleTypeId
                        """)
                .param("dealershipId", dealershipId)
                .param("serviceTypeId", serviceTypeId)
                .param("vehicleTypeId", vehicleTypeId)
                .query(UUID.class)
                .list();
    }

    /**
     * Non-cancelled appointments that hold one of the given bays or technicians and
     * overlap {@code [from, to)} (half-open, D1). Both collections must be non-empty.
     */
    public List<Booking> findBookings(UUID dealershipId, Collection<UUID> bayIds, Collection<UUID> technicianIds,
                                      Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT service_bay_id, technician_id, start_time, end_time
                        FROM appointments
                        WHERE dealership_id = :dealershipId
                          AND status <> :cancelled
                          AND start_time < :to AND end_time > :from
                          AND (service_bay_id IN (:bayIds) OR technician_id IN (:technicianIds))
                        """)
                .param("dealershipId", dealershipId)
                .param("cancelled", AppointmentStatus.CANCELLED.name())
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .param("bayIds", bayIds)
                .param("technicianIds", technicianIds)
                .query((rs, n) -> new Booking(
                        rs.getObject("service_bay_id", UUID.class),
                        rs.getObject("technician_id", UUID.class),
                        rs.getObject("start_time", OffsetDateTime.class).toInstant(),
                        rs.getObject("end_time", OffsetDateTime.class).toInstant()))
                .list();
    }
}
