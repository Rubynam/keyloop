package com.keyloop.scheduler.api.exception.lookup;

import com.keyloop.scheduler.logging.ClientError;

/**
 * {@code from}/{@code to} of a lookup are well-formed but not a valid window; mapped to
 * 400 ProblemDetail. A {@link ClientError}: logged at WARN, not ERROR.
 */
public class InvalidWindowException extends RuntimeException implements ClientError {

    public InvalidWindowException(String message) {
        super(message);
    }
}
