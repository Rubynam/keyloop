package com.keyloop.scheduler.api.exception.appointment;

import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;
import com.keyloop.scheduler.logging.ClientError;

/**
 * The vehicle already has a non-cancelled appointment that overlaps the window (Spec07 D2).
 * Checked before any pair, so no transaction is wasted.
 * Mapped to 422 ProblemDetail by {@code ApiExceptionHandler}. A {@link ClientError}: logged at WARN.
 *
 * <pre>
 * V1 booked 09:00–10:00, request 09:30  →  422, no pair tried
 * </pre>
 */
public class VehicleBusyException extends RuntimeException implements ClientError {

    public VehicleBusyException() {
        super(AppointmentConstants.VEHICLE_BUSY);
    }
}
