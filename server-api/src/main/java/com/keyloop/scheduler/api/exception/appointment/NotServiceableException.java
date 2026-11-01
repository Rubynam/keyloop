package com.keyloop.scheduler.api.exception.appointment;

import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;
import com.keyloop.scheduler.logging.ClientError;

/**
 * No active bay takes the vehicle type, or no technician has the skill and the
 * certification, at any time (candidate outcome NOT_SERVICEABLE).
 * Mapped to 422 ProblemDetail by {@code ApiExceptionHandler}. A {@link ClientError}: logged at WARN.
 *
 * <pre>
 * hydrogen car at a dealership without a hydrogen bay  →  422
 * </pre>
 */
public class NotServiceableException extends RuntimeException implements ClientError {

    public NotServiceableException() {
        super(AppointmentConstants.NOT_SERVICEABLE);
    }
}
