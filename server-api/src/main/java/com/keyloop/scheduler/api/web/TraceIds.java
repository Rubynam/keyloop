package com.keyloop.scheduler.api.web;

import java.util.Optional;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

/**
 * Gives the trace id of the current request, so an error body can name its trace.
 * An unsampled span still has an id, which the log lines carry too; only "no span at
 * all" gives an empty result. Spine AD-4.
 *
 * <pre>
 * inside a request (sampled or not)  →  Optional["4bf92f3577b34da6a3ce929d0e0e4736"]
 * no current span                    →  Optional.empty()
 * </pre>
 */
@Component
public class TraceIds {

    private final Tracer tracer;

    public TraceIds(Tracer tracer) {
        this.tracer = tracer;
    }

    /** The current trace id: 32 lowercase hex characters. */
    public Optional<String> current() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(span.context().traceId()).filter(id -> !id.isEmpty());
    }
}
