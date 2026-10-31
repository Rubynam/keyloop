package com.keyloop.scheduler.logging;

import java.util.Arrays;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

/**
 * Logs one line per call of a controller, service or scheduled method:
 * {@code [ServiceName][method][correlationId][input][output]}.
 */
@Slf4j
@Aspect
public class MethodLoggingAspect {

    // Long payloads (lists of slots, ...) are cut so one call stays one readable line
    private static final int MAX_VALUE_LENGTH = 1000;

    @Around("within(com.keyloop.scheduler..*) && ("
            + "@within(org.springframework.web.bind.annotation.RestController)"
            + " || @within(org.springframework.stereotype.Service)"
            + " || @annotation(org.springframework.scheduling.annotation.Scheduled))")
    public Object logCall(ProceedingJoinPoint joinPoint) throws Throwable {
        // HTTP calls get their id from RequestIdFilter; scheduled jobs start a new one here
        boolean ownsCorrelationId = CorrelationId.current() == null;
        if (ownsCorrelationId) {
            CorrelationId.set(CorrelationId.newId());
        }
        String service = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String method = joinPoint.getSignature().getName();
        String input = formatArgs(joinPoint.getArgs());
        try {
            Object result = joinPoint.proceed();
            String output = isVoid(joinPoint) ? "void" : format(result);
            log.info("[{}][{}][{}][{}][{}]", service, method, CorrelationId.current(), input, output);
            return result;
        } catch (Throwable e) {
            log.error("[{}][{}][{}][{}][{}: {}]", service, method, CorrelationId.current(), input,
                    e.getClass().getSimpleName(), e.getMessage());
            throw e;
        } finally {
            if (ownsCorrelationId) {
                CorrelationId.clear();
            }
        }
    }

    private static boolean isVoid(ProceedingJoinPoint joinPoint) {
        return joinPoint.getSignature() instanceof MethodSignature signature
                && signature.getReturnType() == void.class;
    }

    private static String formatArgs(Object[] args) {
        return Arrays.stream(args).map(MethodLoggingAspect::format).collect(Collectors.joining(", "));
    }

    private static String format(Object value) {
        String text = String.valueOf(value);
        return text.length() <= MAX_VALUE_LENGTH ? text : text.substring(0, MAX_VALUE_LENGTH) + "...";
    }
}
