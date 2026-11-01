package com.keyloop.scheduler.api.exception.appointment;

import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;
import com.keyloop.scheduler.logging.ClientError;

/**
 * The vehicle in the request belongs to another customer (Spec07 §5.1 step 2).
 * Mapped to 400 ProblemDetail by {@code ApiExceptionHandler}. A {@link ClientError}: logged at WARN.
 *
 * <pre>
 * customer C1, vehicle V9 of customer C2  →  400
 * </pre>
 */
public class VehicleOwnerMismatchException extends RuntimeException implements ClientError {

    public VehicleOwnerMismatchException() {
        super(AppointmentConstants.VEHICLE_OWNER_MISMATCH);
    }
}
