package com.keyloop.scheduler.api.exception.appointment;

import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;

/**
 * Internal signal from the appointment repository: another request with the same
 * {@code Idempotency-Key} committed first ({@code 23505}). The service reads the stored
 * row and replays it, or answers 422 when the body differs (Spec07 D3). Never reaches a client.
 *
 * <pre>
 * two parallel calls with k1: A commits, B's insert → 23505 → B returns A's appointment
 * </pre>
 */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException(Throwable cause) {
        super(AppointmentConstants.DUPLICATE_IDEMPOTENCY_KEY, cause);
    }
}
