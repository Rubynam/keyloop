package com.keyloop.scheduler.api.web;

import com.keyloop.scheduler.api.availability.InvalidRangeException;
import com.keyloop.scheduler.api.constant.ErrorMessages;
import com.keyloop.scheduler.api.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 7807 errors (DLD §11). Extending {@link ResponseEntityExceptionHandler} keeps
 * Spring's ProblemDetail 400s for missing / malformed request params and replaces
 * Boot's default handler (spring.mvc.problemdetails.enabled). Unexpected errors are
 * logged and answered with a generic ProblemDetail, never internals.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidRangeException.class)
    ProblemDetail invalidRange(InvalidRangeException e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** DLD §11: PostgreSQL unavailable → 503. */
    @ExceptionHandler(DataAccessException.class)
    ProblemDetail dataAccess(DataAccessException e) {
        log.error("Database error", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ErrorMessages.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorMessages.UNEXPECTED);
    }

    /** Title is the standard reason phrase (Spec05 D9), e.g. "Not Found". */
    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }
}
