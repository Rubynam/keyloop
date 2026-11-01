package com.keyloop.scheduler.api.exception.appointment;

import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;
import com.keyloop.scheduler.logging.ClientError;

/**
 * The local start is not on the slot grid {@code open + n × slot-step} (Spec07 D4).
 * Mapped to 422 ProblemDetail by {@code ApiExceptionHandler}. A {@link ClientError}: logged at WARN.
 *
 * <pre>
 * open 08:00, step 15m: 09:07  →  422 "The start time must be on a 15-minute slot."
 * </pre>
 */
public class OffSlotGridException extends RuntimeException implements ClientError {

    public OffSlotGridException(int stepMinutes) {
        super(AppointmentConstants.OFF_SLOT_GRID.formatted(stepMinutes));
    }
}
