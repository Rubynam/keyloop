package com.keyloop.scheduler.api.exception.appointment;

import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;

/**
 * Internal signal from the appointment repository: a bay or technician foreign key
 * refused the insert ({@code 23503}), e.g. the bay lost its vehicle type after the
 * candidate read. The service tries the next pair (Spec07 §7.2). Never reaches a client.
 *
 * <pre>
 * fk_appointments_bay_type  → ResourceGoneException  next pair
 * </pre>
 */
public class ResourceGoneException extends RuntimeException {

    public ResourceGoneException(String constraint, Throwable cause) {
        super(AppointmentConstants.RESOURCE_GONE.formatted(constraint), cause);
    }
}
