package com.keyloop.scheduler.api.exception.appointment;

import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;

/**
 * Internal signal from the appointment repository: an exclusion constraint refused the
 * insert ({@code 23P01}), so another booking holds the bay, the technician or the vehicle
 * in that window. The service tries the next pair, or stops for the vehicle (Spec07 §7.2).
 * Never reaches a client.
 *
 * <pre>
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

    public SlotTakenException(Resource resource, Throwable cause) {
        super(AppointmentConstants.SLOT_TAKEN.formatted(resource), cause);
        this.resource = resource;
    }

    public Resource resource() {
        return resource;
    }
}
