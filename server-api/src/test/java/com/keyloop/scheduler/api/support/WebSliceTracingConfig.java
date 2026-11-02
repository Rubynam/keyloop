package com.keyloop.scheduler.api.support;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.api.web.TraceIds;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * {@code @WebMvcTest} has no tracing: a stub Tracer with one fixed current trace id lets
 * ApiExceptionHandler run, and lets each error test assert {@code traceId} in the body.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TraceIds.class)
public class WebSliceTracingConfig {

    public static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Bean
    Tracer tracer() {
        Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
        when(tracer.currentSpan().context().traceId()).thenReturn(TRACE_ID);
        return tracer;
    }
}
