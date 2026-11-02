package com.keyloop.scheduler.api.repository.appointment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.keyloop.scheduler.api.exception.appointment.DuplicateIdempotencyKeyException;
import com.keyloop.scheduler.api.exception.appointment.ResourceGoneException;
import com.keyloop.scheduler.api.exception.appointment.SlotTakenException;
import com.keyloop.scheduler.api.exception.appointment.SlotTakenException.Resource;
import com.keyloop.scheduler.api.exception.appointment.VehicleOwnerMismatchException;
import com.keyloop.scheduler.api.model.appointment.NewAppointment;
import com.keyloop.scheduler.api.model.appointment.StoredAppointment;
import com.keyloop.scheduler.api.model.appointment.VehicleRef;

/**
 * Data access of the booking write path: the reads of the service checks and, per
 * (bay, technician) try, lock → check again → insert in one transaction. Database errors
 * come back as small domain signals, so the service never sees SQL or SQLSTATE.
 * Spec07 §5.1, §7.2.
 *
 * <pre>
 * findByIdempotencyKey("k1")                  → Optional[A1]
 * findVehicle(V1)                             → Optional[VehicleRef(V1, C1, SUV)]
 * customerExists(C1)                          → true
 * vehicleHasOverlap(V1, 09:00, 10:00)         → false
 * ── one try, one transaction ──
 * lockResources(T2, B2, V1)                   waits while another try holds T2, B2 or V1
 * findBusyResource(T2, B2, V1, 09:00, 10:00)  → Optional.empty   (or [TECHNICIAN] ...)
 * insert(new A for (B2, T2))                  → A1   or  SlotTakenException(BAY) ...
 * </pre>
 */
public interface AppointmentRepository {

    /**
     * The appointment stored with this {@code Idempotency-Key}, any status.
     *
     * <pre>
     * "k1" → Optional[A1];  unknown → Optional.empty
     * </pre>
     */
    Optional<StoredAppointment> findByIdempotencyKey(String idempotencyKey);

    /**
     * Owner and type of one vehicle; empty when the id is unknown.
     *
     * <pre>
     * V1 → Optional[VehicleRef(V1, C1, SUV)]
     * </pre>
     */
    Optional<VehicleRef> findVehicle(UUID vehicleId);

    /**
     * Whether the customer exists, so the service can answer 404.
     *
     * <pre>
     * C1 → true;  unknown → false
     * </pre>
     */
    boolean customerExists(UUID customerId);

    /**
     * Whether the vehicle already has a non-cancelled appointment that overlaps
     * {@code [start, end)} (half-open, so back-to-back is fine).
     *
     * <pre>
     * V1 booked [09:00, 10:00):  [09:30, 10:30) → true;  [10:00, 11:00) → false
     * </pre>
     */
    boolean vehicleHasOverlap(UUID vehicleId, Instant start, Instant end);

    /**
     * Makes bookings that want the same technician, bay or vehicle wait in line before
     * they write. Takes a transaction lock on each of the three, always in the order
     * technician → bay → vehicle, so two bookings never wait on each other (no deadlock).
     * The locks end with the caller's transaction, so it must run inside it, before
     * {@link #findBusyResource}. The exclusion constraints stay as the last safety net.
     *
     * <pre>
     * try A:  lock T1 → B1 → V1 → check: free → insert → COMMIT (locks released)
     * try B:  lock T1 ··· waits for A ··· → B2 → V2 → check: T1 busy → next pair
     * </pre>
     */
    void lockResources(UUID technicianId, UUID serviceBayId, UUID vehicleId);

    /**
     * Which of the technician, bay or vehicle already has a non-cancelled appointment
     * that overlaps {@code [start, end)}. Run it under {@link #lockResources}: then the
     * answer stays true until the caller commits. The vehicle is named first, since no
     * other pair can help then (422).
     *
     * <pre>
     * V1 booked [09:00, 10:00):  (T2, B2, V1, [09:30, 10:30)) → Optional[VEHICLE]
     * T1 booked [09:00, 10:00):  (T1, B2, V2, [09:00, 10:00)) → Optional[TECHNICIAN]
     * B1 booked [09:00, 10:00):  (T2, B1, V2, [10:00, 11:00)) → Optional.empty  (back-to-back)
     * </pre>
     */
    Optional<Resource> findBusyResource(UUID technicianId, UUID serviceBayId, UUID vehicleId,
                                        Instant start, Instant end);

    /**
     * Inserts one {@code CONFIRMED} appointment. Runs inside the caller's transaction.
     *
     * <pre>
     * insert(A for B2, T2) → StoredAppointment(A1, ..., CONFIRMED)
     * </pre>
     *
     * @throws SlotTakenException               an exclusion constraint refused it (bay, technician or vehicle)
     * @throws ResourceGoneException            a bay / technician foreign key refused it
     * @throws DuplicateIdempotencyKeyException another row already has this key
     * @throws VehicleOwnerMismatchException    the vehicle changed owner after the check
     */
    StoredAppointment insert(NewAppointment appointment);
}
