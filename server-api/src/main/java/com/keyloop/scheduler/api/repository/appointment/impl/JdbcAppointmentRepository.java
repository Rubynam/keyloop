package com.keyloop.scheduler.api.repository.appointment.impl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;
import com.keyloop.scheduler.api.exception.appointment.DuplicateIdempotencyKeyException;
import com.keyloop.scheduler.api.exception.appointment.ResourceGoneException;
import com.keyloop.scheduler.api.exception.appointment.SlotTakenException;
import com.keyloop.scheduler.api.exception.appointment.SlotTakenException.Resource;
import com.keyloop.scheduler.api.exception.appointment.VehicleOwnerMismatchException;
import com.keyloop.scheduler.api.model.AppointmentStatus;
import com.keyloop.scheduler.api.model.appointment.NewAppointment;
import com.keyloop.scheduler.api.model.appointment.StoredAppointment;
import com.keyloop.scheduler.api.model.appointment.VehicleRef;
import com.keyloop.scheduler.api.repository.appointment.AppointmentRepository;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL of the booking write path (Spec07 §5.1, §7.2). The only place that knows SQLSTATE
 * codes and constraint names (V2 schema): it turns them into the small signals the
 * service acts on. Any other database error is rethrown as it is (503).
 *
 * <pre>
 * 23P01 ex_appointments_bay / _technician / _vehicle  →  SlotTakenException(BAY / TECHNICIAN / VEHICLE)
 * 23503 fk_appointments_bay_* / fk_appointments_tech_* →  ResourceGoneException
 * 0 rows: bay or technician no longer active          →  ResourceGoneException
 * 23503 fk_appointments_vehicle_owner                 →  VehicleOwnerMismatchException
 * 23505 appointments_idempotency_key_key              →  DuplicateIdempotencyKeyException
 * 40P01 deadlock (two bookings wait on each other)    →  SlotTakenException(PAIR)
 * </pre>
 */
@Repository
public class JdbcAppointmentRepository implements AppointmentRepository {

    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String DEADLOCK_DETECTED = "40P01";

    private static final String EX_BAY = "ex_appointments_bay";
    private static final String EX_TECHNICIAN = "ex_appointments_technician";
    private static final String EX_VEHICLE = "ex_appointments_vehicle";
    private static final String FK_BAY_PREFIX = "fk_appointments_bay_";
    private static final String FK_TECHNICIAN_PREFIX = "fk_appointments_tech_";
    private static final String FK_VEHICLE_OWNER = "fk_appointments_vehicle_owner";
    private static final String UQ_IDEMPOTENCY_KEY = "appointments_idempotency_key_key";

    private static final String SELECT_STORED = """
            SELECT id, customer_id, vehicle_id, dealership_id, service_type_id,
                   service_bay_id, technician_id, start_time, end_time, status
            FROM appointments
            """;

    private final JdbcClient jdbc;

    public JdbcAppointmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredAppointment> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql(SELECT_STORED + "WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query((rs, n) -> stored(rs))
                .optional();
    }

    @Override
    public Optional<VehicleRef> findVehicle(UUID vehicleId) {
        return jdbc.sql("SELECT id, customer_id, vehicle_type_id FROM vehicles WHERE id = :id")
                .param("id", vehicleId)
                .query((rs, n) -> new VehicleRef(rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class), rs.getObject("vehicle_type_id", UUID.class)))
                .optional();
    }

    @Override
    public boolean customerExists(UUID customerId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM customers WHERE id = :id)")
                .param("id", customerId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public boolean vehicleHasOverlap(UUID vehicleId, Instant start, Instant end) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM appointments
                            WHERE vehicle_id = :vehicleId
                              AND status <> :cancelled
                              AND start_time < :end AND end_time > :start)
                        """)
                .param("vehicleId", vehicleId)
                .param("cancelled", AppointmentStatus.CANCELLED.name())
                .param("start", utc(start))
                .param("end", utc(end))
                .query(Boolean.class)
                .single();
    }

    /**
     * Writes the appointment only if its bay and technician are still {@code active}. The
     * candidate read does not lock, so a resource can be switched off before this insert;
     * no FK covers {@code active}, so the statement checks it itself.
     *
     * <p>{@code FOR SHARE} locks the two rows until commit. A deactivation that is not yet
     * committed makes us wait, then PostgreSQL re-checks {@code active} on the new row:
     * zero rows. A deactivation that comes later waits for our commit. So no appointment
     * commits on a resource whose deactivation committed first. Not guaranteed: a booking
     * that committed before the deactivation stays (cancelling it is a business step). A
     * deactivation that locks both rows in the other order can deadlock with us: 40P01.
     *
     * <pre>
     * bay B2 active,   tech T2 active    →  A1
     * bay B2 inactive (or T2 inactive)   →  0 rows  →  ResourceGoneException  next pair
     * </pre>
     */
    @Override
    public StoredAppointment insert(NewAppointment a) {
        try {
            UUID id = jdbc.sql("""
                            INSERT INTO appointments (customer_id, vehicle_id, dealership_id, service_type_id,
                                                      technician_id, service_bay_id, vehicle_type_id,
                                                      start_time, end_time, status, idempotency_key)
                            SELECT :customerId, :vehicleId, :dealershipId, :serviceTypeId,
                                   t.id, b.id, :vehicleTypeId,
                                   :start, :end, :status, :key
                            FROM service_bays b, technicians t
                            WHERE b.id = :serviceBayId AND b.active
                              AND t.id = :technicianId AND t.active
                            FOR SHARE OF b, t
                            RETURNING id
                            """)
                    .param("customerId", a.customerId())
                    .param("vehicleId", a.vehicleId())
                    .param("dealershipId", a.dealershipId())
                    .param("serviceTypeId", a.serviceTypeId())
                    .param("technicianId", a.technicianId())
                    .param("serviceBayId", a.serviceBayId())
                    .param("vehicleTypeId", a.vehicleTypeId())
                    .param("start", utc(a.start()))
                    .param("end", utc(a.end()))
                    .param("status", AppointmentStatus.CONFIRMED.name())
                    .param("key", a.idempotencyKey())
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(() -> new ResourceGoneException(AppointmentConstants.INACTIVE_RESOURCE, null));
            return new StoredAppointment(id, a.customerId(), a.vehicleId(), a.dealershipId(), a.serviceTypeId(),
                    a.serviceBayId(), a.technicianId(), a.start(), a.end(), AppointmentStatus.CONFIRMED);
        } catch (DataAccessException e) {
            throw translate(e);
        }
    }

    /** SQLSTATE + constraint name → domain signal; anything else stays a {@link DataAccessException}. */
    static RuntimeException translate(DataAccessException e) {
        ServerErrorMessage error = serverError(e);
        if (error == null) {
            return e;
        }
        // Parallel inserts wait on each other's uncommitted exclusion entries; PostgreSQL
        // aborts one of them. The other may commit, so this pair is lost, not broken.
        if (DEADLOCK_DETECTED.equals(error.getSQLState())) {
            return new SlotTakenException(Resource.PAIR, e);
        }
        if (error.getConstraint() == null) {
            return e;
        }
        String constraint = error.getConstraint();
        switch (error.getSQLState()) {
            case EXCLUSION_VIOLATION -> {
                switch (constraint) {
                    case EX_BAY -> {
                        return new SlotTakenException(Resource.BAY, e);
                    }
                    case EX_TECHNICIAN -> {
                        return new SlotTakenException(Resource.TECHNICIAN, e);
                    }
                    case EX_VEHICLE -> {
                        return new SlotTakenException(Resource.VEHICLE, e);
                    }
                    default -> {
                        return e;
                    }
                }
            }
            case FOREIGN_KEY_VIOLATION -> {
                if (constraint.startsWith(FK_BAY_PREFIX) || constraint.startsWith(FK_TECHNICIAN_PREFIX)) {
                    return new ResourceGoneException(constraint, e);
                }
                if (constraint.equals(FK_VEHICLE_OWNER)) {
                    return new VehicleOwnerMismatchException();
                }
                return e;
            }
            case UNIQUE_VIOLATION -> {
                return constraint.equals(UQ_IDEMPOTENCY_KEY) ? new DuplicateIdempotencyKeyException(e) : e;
            }
            default -> {
                return e;
            }
        }
    }

    private static ServerErrorMessage serverError(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psql) {
                return psql.getServerErrorMessage();
            }
        }
        return null;
    }

    private static StoredAppointment stored(ResultSet rs) throws SQLException {
        return new StoredAppointment(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("vehicle_id", UUID.class),
                rs.getObject("dealership_id", UUID.class),
                rs.getObject("service_type_id", UUID.class),
                rs.getObject("service_bay_id", UUID.class),
                rs.getObject("technician_id", UUID.class),
                rs.getObject("start_time", OffsetDateTime.class).toInstant(),
                rs.getObject("end_time", OffsetDateTime.class).toInstant(),
                AppointmentStatus.valueOf(rs.getString("status")));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
