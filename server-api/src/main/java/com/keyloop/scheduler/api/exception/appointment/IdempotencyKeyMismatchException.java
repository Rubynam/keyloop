package com.keyloop.scheduler.api.exception.appointment;

import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;
import com.keyloop.scheduler.logging.ClientError;

/**
 * The Idempotency-Key is already stored for a request with other fields (Spec07 D3).
 * Returning the old booking would hide a client bug.
 * Mapped to 422 ProblemDetail by {@code ApiExceptionHandler}. A {@link ClientError}: logged at WARN.
 *
 * <pre>
 * k1 → A1 at 09:00;  k1 again with 10:00  →  422
 * </pre>
 */
public class IdempotencyKeyMismatchException extends RuntimeException implements ClientError {

    public IdempotencyKeyMismatchException() {
        super(AppointmentConstants.IDEMPOTENCY_KEY_MISMATCH);
    }
}
