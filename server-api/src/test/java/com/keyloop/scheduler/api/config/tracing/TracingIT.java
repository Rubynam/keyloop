package com.keyloop.scheduler.api.config.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.keyloop.scheduler.api.support.Fixtures;
import com.keyloop.scheduler.api.support.InMemoryTracingConfig;
import com.keyloop.scheduler.api.support.MdcLogCapture;
import com.keyloop.scheduler.api.support.TestcontainersConfig;
import com.keyloop.scheduler.logging.CorrelationId;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story tracing-baseline AC1–AC5 on real Postgres + Redis. The OTLP endpoint is a closed
 * port, so every test also runs "Jaeger down" (AC2) and nothing ever reaches a real Jaeger.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.opentelemetry.tracing.export.otlp.endpoint=" + TracingIT.CLOSED_ENDPOINT,
        "management.tracing.sampling.probability=1.0"
})
@AutoConfigureTracing
@Import({TestcontainersConfig.class, InMemoryTracingConfig.class})
class TracingIT {

    /** Port 1 refuses connections: the exporter fails on every batch. */
    static final String CLOSED_ENDPOINT = "http://localhost:1/v1/traces";
    static final String DAY = "2030-01-07";
    private static final String TRACE_ID = "^[0-9a-f]{32}$";

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private InMemorySpanExporter exporter;
    @Autowired
    private SdkTracerProvider tracerProvider;

    private final HttpClient http = HttpClient.newHttpClient();
    private Fixtures fixtures;
    private UUID dealer;
    private UUID service;
    private UUID vehicleType;
    private UUID bay;
    private UUID tech;

    @BeforeEach
    void seed() {
        fixtures = new Fixtures(jdbc);
        dealer = fixtures.dealership("Asia/Ho_Chi_Minh", "08:00", "17:00");
        service = fixtures.serviceType(60);
        vehicleType = fixtures.vehicleType();
        bay = fixtures.bay(dealer, vehicleType);
        tech = fixtures.technician(dealer, service, vehicleType);
        InMemoryTracingConfig.flush(tracerProvider, exporter);
        exporter.reset();
    }

    @Test
    void ac1AvailabilityIsOneTraceWithJdbcAndRedisChildren() throws Exception {
        HttpResponse<String> response = availability(dealer, UUID.randomUUID().toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        List<SpanData> trace = awaitTraceOfServerSpan();
        SpanData root = serverSpan(trace);
        assertThat(root.getParentSpanContext().isValid()).isFalse();
        assertThat(trace).filteredOn(TracingIT::isJdbc).isNotEmpty()
                .allSatisfy(span -> assertThat(span.getParentSpanContext().isValid()).isTrue());
        assertThat(trace).filteredOn(TracingIT::isRedis).isNotEmpty()
                .allSatisfy(span -> assertThat(span.getParentSpanContext().isValid()).isTrue());
    }

    @Test
    void ac2ExporterDownStillAnswers() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(availability(dealer, UUID.randomUUID().toString()).statusCode()).isEqualTo(200);
        }
        // A failed OTLP batch does not stop the other exporter: spans still reach memory
        await().atMost(InMemoryTracingConfig.EXPORT_TIMEOUT).untilAsserted(() ->
                assertThat(InMemoryTracingConfig.flush(tracerProvider, exporter))
                        .filteredOn(span -> span.getKind() == SpanKind.SERVER).hasSize(5));
    }

    @Test
    void ac3EveryLogLineOfARequestHasItsTraceIdAndCorrelationId() throws Exception {
        String requestId = UUID.randomUUID().toString();
        List<Map<String, String>> lines;
        try (MdcLogCapture logs = MdcLogCapture.open()) {
            assertThat(availability(dealer, requestId).statusCode()).isEqualTo(200);
            lines = logs.withEntry(CorrelationId.MDC_KEY, requestId);
        }

        String traceId = serverSpan(awaitTraceOfServerSpan()).getTraceId();
        assertThat(lines).isNotEmpty()
                .allSatisfy(mdc -> assertThat(mdc).containsEntry("traceId", traceId).containsKey("spanId"));
    }

    @Test
    void ac4EveryErrorBodyHasTheExportedTraceId() throws Exception {
        fixtures.appointment(dealer, service, vehicleType, bay, tech, at("09:00"), at("10:00"), "CONFIRMED");
        UUID customer = fixtures.customer();
        UUID vehicle = fixtures.vehicle(customer, vehicleType);

        assertTraceIdMatchesExport(availability(UUID.randomUUID(), UUID.randomUUID().toString()), 404);
        // Inherited Spring handler (missing query parameter) and our @Valid override
        assertTraceIdMatchesExport(get("/api/v1/availability"), 400);
        assertTraceIdMatchesExport(book("{}"), 400);
        assertTraceIdMatchesExport(book(body(customer, vehicle, local("09:00"))), 409);
        assertTraceIdMatchesExport(book(body(customer, vehicle, local("11:07"))), 422);
    }

    @Test
    void ac5HealthChecksAndTheirDataAccessAreNotExported() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        }
        // Control: spans are exported in order, so once this request is there, the checks would be too
        availability(dealer, UUID.randomUUID().toString());
        List<SpanData> trace = awaitTraceOfServerSpan();

        List<SpanData> spans = InMemoryTracingConfig.flush(tracerProvider, exporter);
        assertThat(spans).filteredOn(span -> span.getKind() == SpanKind.SERVER).containsExactly(serverSpan(trace));
        assertThat(spans).filteredOn(span -> !span.getParentSpanContext().isValid())
                .noneMatch(span -> isJdbc(span) || isRedis(span));
    }

    private void assertTraceIdMatchesExport(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode traceIdNode = json.readTree(response.body()).get("traceId");
        assertThat(traceIdNode).as(response.body()).isNotNull();
        String traceId = traceIdNode.asString();
        assertThat(traceId).matches(TRACE_ID);
        await().atMost(InMemoryTracingConfig.EXPORT_TIMEOUT).untilAsserted(() ->
                assertThat(InMemoryTracingConfig.flush(tracerProvider, exporter))
                        .anySatisfy(span -> {
                            assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
                            assertThat(span.getTraceId()).isEqualTo(traceId);
                        }));
    }

    /** All exported spans of the trace of the availability request. */
    private List<SpanData> awaitTraceOfServerSpan() {
        return await().atMost(InMemoryTracingConfig.EXPORT_TIMEOUT).until(() -> {
            List<SpanData> spans = InMemoryTracingConfig.flush(tracerProvider, exporter);
            return spans.stream().filter(TracingIT::isAvailabilityRequest).findFirst()
                    .map(server -> spans.stream().filter(span -> span.getTraceId().equals(server.getTraceId()))
                            .toList())
                    .orElse(List.of());
        }, trace -> trace.stream().anyMatch(TracingIT::isJdbc) && trace.stream().anyMatch(TracingIT::isRedis));
    }

    private static SpanData serverSpan(List<SpanData> trace) {
        return trace.stream().filter(TracingIT::isAvailabilityRequest).findFirst().orElseThrow();
    }

    private static boolean isAvailabilityRequest(SpanData span) {
        return span.getKind() == SpanKind.SERVER
                && "/api/v1/availability".equals(span.getAttributes().get(AttributeKey.stringKey("uri")));
    }

    static boolean isJdbc(SpanData span) {
        return span.getAttributes().asMap().keySet().stream().anyMatch(key -> key.getKey().startsWith("jdbc."));
    }

    static boolean isRedis(SpanData span) {
        return span.getAttributes().asMap().entrySet().stream()
                .anyMatch(e -> e.getKey().getKey().equals("db.system") && "redis".equals(e.getValue()));
    }

    private HttpResponse<String> availability(UUID dealership, String requestId) throws Exception {
        String uri = "http://localhost:" + port + "/api/v1/availability?dealershipId=" + dealership
                + "&serviceTypeId=" + service + "&vehicleTypeId=" + vehicleType + "&from=" + DAY + "&to=" + DAY;
        return http.send(HttpRequest.newBuilder(URI.create(uri)).header("X-Request-Id", requestId).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> book(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/appointments"))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String body(UUID customer, UUID vehicle, String start) {
        return """
                {"customerId":"%s","vehicleId":"%s","dealershipId":"%s","serviceTypeId":"%s","startTime":"%s"}
                """.formatted(customer, vehicle, dealer, service, start);
    }

    private static String local(String time) {
        return DAY + "T" + time + ":00+07:00";
    }

    private static OffsetDateTime at(String localTime) {
        return OffsetDateTime.parse(local(localTime));
    }
}
