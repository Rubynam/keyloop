package com.keyloop.scheduler.logging;

import java.util.UUID;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

/** The id that ties every log line of one request / job run together, kept in the MDC. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CorrelationId {

    public static final String MDC_KEY = "correlationId";

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static void set(String correlationId) {
        MDC.put(MDC_KEY, correlationId);
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
