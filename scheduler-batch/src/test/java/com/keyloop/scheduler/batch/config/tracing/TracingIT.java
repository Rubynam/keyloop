package com.keyloop.scheduler.batch.config.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.keyloop.scheduler.batch.support.InMemoryTracingConfig;
import com.keyloop.scheduler.batch.support.TestcontainersConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Story tracing-baseline AC5–AC6 on real Postgres + Redis: the relay ticks every 500 ms
 * with nothing due, and the heartbeat cron fires every second (ShedLock lets one run per
 * 10 s). OTLP export is off: nothing reaches a real Jaeger, and a failing OTLP batch cannot
 * hold back the in-memory export (the "Jaeger down" case is server-api TracingIT AC2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.locations=filesystem:../server-api/src/main/resources/db/migration",
        "spring.flyway.default-schema=public",
        "spring.flyway.schemas=public",
        "management.tracing.export.otlp.enabled=false",
        "management.tracing.sampling.probability=1.0",
        "scheduler.outbox.relay.enabled=true",
        "scheduler.jobs.heartbeat.cron=* * * * * *"
})
@AutoConfigureTracing
@Import({TestcontainersConfig.class, InMemoryTracingConfig.class})
class TracingIT {

    private static final AttributeKey<String> CODE_NAMESPACE = AttributeKey.stringKey("code.namespace");
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(3);

    @LocalServerPort
    private int port;
    @Autowired
    private InMemorySpanExporter exporter;
    @Autowired
    private SdkTracerProvider tracerProvider;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void ac5NoRelayActuatorOrOrphanDataAccessSpans() throws Exception {
        InMemoryTracingConfig.flush(tracerProvider, exporter);
        exporter.reset();
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> health = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
        }
        // About 6 empty relay ticks happen in this window
        await().pollDelay(QUIET_PERIOD).atMost(QUIET_PERIOD.multipliedBy(2)).until(() -> true);

        List<SpanData> spans = InMemoryTracingConfig.flush(tracerProvider, exporter);
        // Control: export works in this window (heartbeat ticks, even the ones ShedLock skips)
        assertThat(spans).anyMatch(TracingIT::isHeartbeatRun);
        assertThat(spans).noneMatch(span -> span.getKind() == SpanKind.SERVER);
        assertThat(spans).noneMatch(TracingIT::isRelayTick);
        assertThat(spans).filteredOn(span -> !span.getParentSpanContext().isValid())
                .noneMatch(span -> isJdbc(span) || isRedis(span));
    }

    @Test
    void ac6HeartbeatIsOneTraceFromScheduledTaskDownToJdbcAndRedis() {
        // The root ends last: wait until it is exported, not only the job and step spans
        List<SpanData> trace = await().atMost(Duration.ofSeconds(20)).until(this::heartbeatTrace,
                spans -> spans.stream().anyMatch(TracingIT::isHeartbeatRun)
                        && spans.stream().anyMatch(span -> span.getName().equals("spring.batch.step")));

        SpanData root = trace.stream().filter(span -> !span.getParentSpanContext().isValid()).findFirst().orElseThrow();
        assertThat(isHeartbeatRun(root)).isTrue();
        assertThat(trace).extracting(SpanData::getName).contains("spring.batch.job", "spring.batch.step");
        assertThat(trace).filteredOn(TracingIT::isJdbc).isNotEmpty();
        assertThat(trace).filteredOn(TracingIT::isRedis).isNotEmpty();
        assertThat(trace).filteredOn(span -> span != root)
                .allSatisfy(span -> assertThat(span.getParentSpanContext().isValid()).isTrue());
    }

    /** Spans of the latest exported trace whose JobScheduler root and Batch job are both in, or empty. */
    private List<SpanData> heartbeatTrace() {
        List<SpanData> spans = InMemoryTracingConfig.flush(tracerProvider, exporter);
        return spans.stream()
                .filter(span -> span.getName().equals("spring.batch.job"))
                .map(SpanData::getTraceId)
                .filter(traceId -> spans.stream()
                        .anyMatch(span -> span.getTraceId().equals(traceId) && isHeartbeatRun(span)))
                .reduce((first, second) -> second)
                .map(traceId -> spans.stream().filter(span -> span.getTraceId().equals(traceId)).toList())
                .orElse(List.of());
    }

    private static boolean isJdbc(SpanData span) {
        return span.getAttributes().asMap().keySet().stream().anyMatch(key -> key.getKey().startsWith("jdbc."));
    }

    private static boolean isRedis(SpanData span) {
        return "redis".equals(span.getAttributes().get(AttributeKey.stringKey("db.system")));
    }

    private static boolean isHeartbeatRun(SpanData span) {
        String namespace = span.getAttributes().get(CODE_NAMESPACE);
        return !span.getParentSpanContext().isValid() && namespace != null && namespace.endsWith(".JobScheduler");
    }

    private static boolean isRelayTick(SpanData span) {
        String namespace = span.getAttributes().get(CODE_NAMESPACE);
        return namespace != null && namespace.endsWith(".OutboxRelay");
    }
}
