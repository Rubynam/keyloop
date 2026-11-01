package com.keyloop.scheduler.api.exception.appointment;

import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;
import com.keyloop.scheduler.logging.ClientError;

/**
 * Every (bay, technician) pair was taken, or none was free from the start. The client
 * should pick another slot (Spec04 §6).
 * Mapped to 409 ProblemDetail by {@code ApiExceptionHandler}. A {@link ClientError}: logged at WARN.
 *
 * <pre>
 * pairs (B1,T1) (B2,T2): both 23P01  →  409 "Booking failed: ..."
 * </pre>
 */
public class BookingConflictException extends RuntimeException implements ClientError {

    public BookingConflictException() {
        super(AppointmentConstants.BOOKING_FAILED);
    }
}
