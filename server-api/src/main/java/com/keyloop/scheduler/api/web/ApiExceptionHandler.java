package com.keyloop.scheduler.api.web;

import com.keyloop.scheduler.api.availability.InvalidRangeException;
import com.keyloop.scheduler.api.availability.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 7807 errors (DLD §11). Extending {@link ResponseEntityExceptionHandler} keeps
 * Spring's ProblemDetail 400s for missing / malformed request params and replaces
 * Boot's default handler (spring.mvc.problemdetails.enabled).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Not Found");
        return problem;
    }

    @ExceptionHandler(InvalidRangeException.class)
    ProblemDetail invalidRange(InvalidRangeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Bad Request");
        return problem;
    }
}
