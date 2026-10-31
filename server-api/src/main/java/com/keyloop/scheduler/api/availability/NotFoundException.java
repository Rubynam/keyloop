package com.keyloop.scheduler.api.availability;

/** A referenced entity does not exist; mapped to 404 ProblemDetail by {@code ApiExceptionHandler}. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String entity, Object id) {
        super(entity + " " + id + " not found");
    }
}
