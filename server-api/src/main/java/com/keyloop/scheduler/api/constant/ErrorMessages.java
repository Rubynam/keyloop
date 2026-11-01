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
 * INVALID_REQUEST + errors[{field: ERROR_FIELD, message: ERROR_MESSAGE}]  →  400 body
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ErrorMessages {

    public static final String NOT_FOUND = "%s %s not found";

    public static final String ENTITY_DEALERSHIP = "Dealership";
    public static final String ENTITY_SERVICE_TYPE = "Service type";
    public static final String ENTITY_VEHICLE_TYPE = "Vehicle type";
    public static final String ENTITY_CUSTOMER = "Customer";
    public static final String ENTITY_VEHICLE = "Vehicle";

    /** 400 from the controller edge: {@code detail} plus one {@code errors[]} entry per bad field. */
    public static final String INVALID_REQUEST = "Invalid request";
    public static final String ERRORS_PROPERTY = "errors";
    public static final String ERROR_FIELD = "field";
    public static final String ERROR_MESSAGE = "message";
    /** {@code errors[].message} when the JSON value cannot be read (bad UUID, time without offset, ...). */
    public static final String UNREADABLE_VALUE = "invalid value";
    /** {@code errors[].field} when the body itself cannot be read (not JSON, empty). */
    public static final String BODY_FIELD = "body";
    public static final String MISSING_HEADER = "required header is missing";

    public static final String SERVICE_UNAVAILABLE = "The service is temporarily unavailable, please retry later.";
    public static final String UNEXPECTED = "An unexpected error occurred.";
}
