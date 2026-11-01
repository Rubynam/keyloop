package com.keyloop.scheduler.api.exception;

import com.keyloop.scheduler.api.constant.ErrorMessages;

/** A referenced entity does not exist; mapped to 404 ProblemDetail by {@code ApiExceptionHandler}. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String entity, Object id) {
        super(ErrorMessages.NOT_FOUND.formatted(entity, id));
    }
}
