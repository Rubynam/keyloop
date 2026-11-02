package com.keyloop.scheduler.api.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.keyloop.scheduler.api.constant.ErrorMessages;
import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;
import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.exception.appointment.BookingConflictException;
import com.keyloop.scheduler.api.exception.appointment.IdempotencyKeyMismatchException;
import com.keyloop.scheduler.api.exception.appointment.NotServiceableException;
import com.keyloop.scheduler.api.exception.appointment.OffSlotGridException;
import com.keyloop.scheduler.api.exception.appointment.OutsideBusinessHoursException;
import com.keyloop.scheduler.api.exception.appointment.VehicleBusyException;
import com.keyloop.scheduler.api.exception.appointment.VehicleOwnerMismatchException;
import com.keyloop.scheduler.api.exception.availability.InvalidRangeException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/**
 * RFC 7807 errors (DLD §11, Spec07 §6). The only place that builds an error body.
 * Extending {@link ResponseEntityExceptionHandler} keeps Spring's ProblemDetail 400s
 * for malformed requests and replaces Boot's default handler
 * (spring.mvc.problemdetails.enabled). Edge validation errors add an {@code errors[]}
 * list. Unexpected errors are logged and answered with a generic body, never internals.
 * Every body carries the request's {@code traceId}, so a client can quote it and we can
 * open the trace (spine AD-4).
 *
 * <pre>
 * 400 { title: "Bad Request", status: 400, detail: "Invalid request",
 *       errors: [ { field: "startTime", message: "must be a future date" } ],
 *       traceId: "4bf92f3577b34da6a3ce929d0e0e4736" }
 * 409 { title: "Booking failed", status: 409, detail: "Booking failed: no technician ...",
 *       traceId: "4bf92f3577b34da6a3ce929d0e0e4736" }
 * </pre>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final TraceIds traceIds;

    public ApiExceptionHandler(TraceIds traceIds) {
        this.traceIds = traceIds;
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return withTraceId(problem(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(InvalidRangeException.class)
    ProblemDetail invalidRange(InvalidRangeException e) {
        return withTraceId(problem(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    /** Spec07 §5.1 step 2: the vehicle belongs to another customer → 400. */
    @ExceptionHandler(VehicleOwnerMismatchException.class)
    ProblemDetail vehicleOwnerMismatch(VehicleOwnerMismatchException e) {
        return withTraceId(problem(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    /** Spec07 §6: valid shape, but a booking rule fails → 422. */
    @ExceptionHandler({VehicleBusyException.class, IdempotencyKeyMismatchException.class,
            NotServiceableException.class, OutsideBusinessHoursException.class, OffSlotGridException.class})
    ProblemDetail bookingRule(RuntimeException e) {
        return withTraceId(problem(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage()));
    }

    /** Spec04 §6: every pair taken → 409 with its own title. */
    @ExceptionHandler(BookingConflictException.class)
    ProblemDetail bookingConflict(BookingConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle(AppointmentConstants.BOOKING_FAILED_TITLE);
        return withTraceId(problem);
    }

    /** DLD §11: PostgreSQL unavailable → 503. */
    @ExceptionHandler(DataAccessException.class)
    ProblemDetail dataAccess(DataAccessException e) {
        log.error("Database error", e);
        return withTraceId(problem(HttpStatus.SERVICE_UNAVAILABLE, ErrorMessages.SERVICE_UNAVAILABLE));
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unexpected error", e);
        return withTraceId(problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorMessages.UNEXPECTED));
    }

    /** {@code @Valid @RequestBody} failed: one entry per bad field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::fieldError)
                .toList();
        return handleExceptionInternal(ex, invalidRequest(errors), headers, status, request);
    }

    /** Built-in method validation (header and body constraints): one entry per bad value. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors().forEach(e -> errors.add(fieldError(e)));
            } else {
                String name = parameterName(result.getMethodParameter());
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.add(error(name, error.getDefaultMessage()));
                }
            }
        }
        return handleExceptionInternal(ex, invalidRequest(errors), headers, status, request);
    }

    /** JSON that cannot be read: bad UUID text, a time without offset, not JSON at all. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = ErrorMessages.BODY_FIELD;
        // The Jackson error with the JSON path may wrap a java.time parse error
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
                String path = jackson.getPath().stream()
                        .map(JacksonException.Reference::getPropertyName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("."));
                // Index-only path (the body is a JSON array): no field to name
                if (!path.isEmpty()) {
                    field = path;
                }
                break;
            }
        }
        List<Map<String, String>> errors = List.of(error(field, ErrorMessages.UNREADABLE_VALUE));
        return handleExceptionInternal(ex, invalidRequest(errors), headers, status, request);
    }

    /** A missing required header gets the same {@code errors[]} shape; other binding errors stay as they are. */
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing) {
            List<Map<String, String>> errors = List.of(error(missing.getHeaderName(), ErrorMessages.MISSING_HEADER));
            return handleExceptionInternal(ex, invalidRequest(errors), headers, status, request);
        }
        return super.handleServletRequestBindingException(ex, headers, status, request);
    }

    /** Every inherited Spring 400/405/415/... and every override above ends here: add the trace id. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            withTraceId(problem);
        }
        return response;
    }

    /** The one place that sets {@code traceId}; left out when there is no current span. */
    private ProblemDetail withTraceId(ProblemDetail problem) {
        traceIds.current().ifPresent(id -> problem.setProperty(ErrorMessages.TRACE_ID_PROPERTY, id));
        return problem;
    }

    /** Title is the standard reason phrase (Spec05 D9), e.g. "Not Found". */
    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }

    private static ProblemDetail invalidRequest(List<Map<String, String>> errors) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ErrorMessages.INVALID_REQUEST);
        problem.setProperty(ErrorMessages.ERRORS_PROPERTY, errors);
        return problem;
    }

    private static Map<String, String> fieldError(FieldError error) {
        return error(error.getField(), error.getDefaultMessage());
    }

    private static Map<String, String> error(String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put(ErrorMessages.ERROR_FIELD, field);
        error.put(ErrorMessages.ERROR_MESSAGE, message);
        return error;
    }

    /** The header name for {@code @RequestHeader}, else the Java parameter name. */
    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.value().isEmpty()) {
            return header.value();
        }
        return parameter.getParameterName();
    }
}
