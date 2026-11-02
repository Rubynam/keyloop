package com.keyloop.scheduler.api.constant.appointment;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Fixed values of the booking path: the key length limit and the error text of every
 * booking refusal. Clients and tests read this text, so do not change a character.
 * Spec07 §6, Spec04 §6.
 *
 * <pre>
 * OFF_SLOT_GRID.formatted(15)  →  "The start time must be on a 15-minute slot."
 * BOOKING_FAILED               →  409 detail when every pair is taken
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AppointmentConstants {

    /** Column {@code appointments.idempotency_key} is VARCHAR(128). */
    public static final int IDEMPOTENCY_KEY_MAX = 128;

    public static final String VEHICLE_OWNER_MISMATCH = "The vehicle does not belong to this customer.";
    public static final String VEHICLE_BUSY = "The vehicle already has an appointment at this time.";
    public static final String IDEMPOTENCY_KEY_MISMATCH =
            "This Idempotency-Key was already used for a different request.";
    public static final String NOT_SERVICEABLE = "No bay or technician at this dealership can service this vehicle.";
    public static final String OUTSIDE_BUSINESS_HOURS = "The time is outside the dealership's business hours.";
    public static final String OFF_SLOT_GRID = "The start time must be on a %d-minute slot.";

    /** Spec04 §6: title and detail of the 409 after every pair failed. */
    public static final String BOOKING_FAILED_TITLE = "Booking failed";
    public static final String BOOKING_FAILED = "Booking failed: no technician or service bay is available for the "
            + "requested time. Please choose another slot.";

    /** Internal signals, never shown to a client. */
    public static final String SLOT_TAKEN = "Slot already taken: %s";
    public static final String RESOURCE_GONE = "Bay or technician no longer fits: %s";
    /** Reason in {@link #RESOURCE_GONE} when the insert found the bay or technician inactive. */
    public static final String INACTIVE_RESOURCE = "bay or technician inactive";
    public static final String DUPLICATE_IDEMPOTENCY_KEY = "Idempotency-Key already stored";
}
