package com.keyloop.scheduler.api.exception.appointment;

import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;

/**
 * Internal signal of the booking write path: another booking holds the bay, the
 * technician or the vehicle in that window. Mostly the check under the resource locks
 * finds it; an exclusion constraint ({@code 23P01}) is the last safety net. The service
 * tries the next pair, or stops for the vehicle (Spec07 §7.2). Never reaches a client.
 *
 * <pre>
 * check under lock: busy X    → SlotTakenException(X)           next pair / 422
 * ex_appointments_bay         → SlotTakenException(BAY)         next pair
 * ex_appointments_technician  → SlotTakenException(TECHNICIAN)  next pair
 * ex_appointments_vehicle     → SlotTakenException(VEHICLE)     stop, 422
 * 40P01 deadlock              → SlotTakenException(PAIR)        next pair
 * </pre>
 */
public class SlotTakenException extends RuntimeException {

    /**
     * Which resource is already booked. {@code PAIR}: PostgreSQL broke a deadlock between
     * two bookings that wait on each other's bay or technician, and we lost this pair.
     */
    public enum Resource { BAY, TECHNICIAN, VEHICLE, PAIR }

    private final Resource resource;

    /** Found by the check under the resource locks: no database error behind it. */
    public SlotTakenException(Resource resource) {
        this(resource, null);
    }

    public SlotTakenException(Resource resource, Throwable cause) {
        super(AppointmentConstants.SLOT_TAKEN.formatted(resource), cause);
        this.resource = resource;
    }

    public Resource resource() {
        return resource;
    }
}
