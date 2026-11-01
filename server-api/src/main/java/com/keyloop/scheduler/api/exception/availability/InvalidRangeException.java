package com.keyloop.scheduler.api.exception.availability;

/** {@code from}/{@code to} are well-formed but not a valid range; mapped to 400 ProblemDetail. */
public class InvalidRangeException extends RuntimeException {

    public InvalidRangeException(String message) {
        super(message);
    }
}
