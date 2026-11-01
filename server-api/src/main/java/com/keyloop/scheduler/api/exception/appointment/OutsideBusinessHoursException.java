package com.keyloop.scheduler.api.exception.appointment;

import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;
import com.keyloop.scheduler.logging.ClientError;

/**
 * The window {@code [start, start + duration)} is not inside the dealership's local
 * business hours (Spec07 §5.1 step 4).
 * Mapped to 422 ProblemDetail by {@code ApiExceptionHandler}. A {@link ClientError}: logged at WARN.
 *
 * <pre>
 * open 08:00–17:00, 16:30 + 60 min  →  422
 * </pre>
 */
public class OutsideBusinessHoursException extends RuntimeException implements ClientError {

    public OutsideBusinessHoursException() {
        super(AppointmentConstants.OUTSIDE_BUSINESS_HOURS);
    }
}
