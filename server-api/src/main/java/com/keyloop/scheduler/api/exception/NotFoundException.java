package com.keyloop.scheduler.api.exception;

import com.keyloop.scheduler.api.constant.ErrorMessages;
import com.keyloop.scheduler.logging.ClientError;

/**
 * A referenced entity does not exist; mapped to 404 ProblemDetail by {@code ApiExceptionHandler}.
 * A {@link ClientError}: logged at WARN, not ERROR.
 */
public class NotFoundException extends RuntimeException implements ClientError {

    public NotFoundException(String entity, Object id) {
        super(ErrorMessages.NOT_FOUND.formatted(entity, id));
    }
}
