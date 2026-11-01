package com.keyloop.scheduler.api.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Error text of the API error contract, used by more than one feature or by
 * {@code ApiExceptionHandler}. It ends up in the {@code detail} of a ProblemDetail
 * body, and tests assert it, so do not change a character (DLD §11).
 *
 * <pre>
 * NOT_FOUND.formatted(ENTITY_DEALERSHIP, id)  →  "Dealership 3f2a… not found"
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ErrorMessages {

    public static final String NOT_FOUND = "%s %s not found";

    public static final String ENTITY_DEALERSHIP = "Dealership";
    public static final String ENTITY_SERVICE_TYPE = "Service type";
    public static final String ENTITY_VEHICLE_TYPE = "Vehicle type";

    public static final String SERVICE_UNAVAILABLE = "The service is temporarily unavailable, please retry later.";
    public static final String UNEXPECTED = "An unexpected error occurred.";
}
