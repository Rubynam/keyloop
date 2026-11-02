package com.keyloop.scheduler.batch.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Collects exported spans in memory (spine: never call Jaeger from a test). Use it with
 * {@code @AutoConfigureTracing}: without it {@code @SpringBootTest} turns export off.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InMemoryTracingConfig {

    /** Wait for one forced flush; short, because the failing OTLP exporter retries in the same flush. */
    public static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(1);
    /** Upper bound for exported spans to show up. */
    public static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }

    /** Pushes finished spans through the batch processor now, then returns what was exported. */
    public static List<SpanData> flush(SdkTracerProvider tracerProvider, InMemorySpanExporter exporter) {
        tracerProvider.forceFlush().join(FLUSH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return exporter.getFinishedSpanItems();
    }
}
