package com.keyloop.scheduler.api.exception.availability;

import com.keyloop.scheduler.logging.ClientError;

/**
 * {@code from}/{@code to} are well-formed but not a valid range; mapped to 400 ProblemDetail.
 * A {@link ClientError}: logged at WARN, not ERROR.
 */
public class InvalidRangeException extends RuntimeException implements ClientError {

    public InvalidRangeException(String message) {
        super(message);
    }
}
