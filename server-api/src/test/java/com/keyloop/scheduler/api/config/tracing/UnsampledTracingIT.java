package com.keyloop.scheduler.api.config.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import com.keyloop.scheduler.api.support.InMemoryTracingConfig;
import com.keyloop.scheduler.api.support.TestcontainersConfig;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/** Story tracing-baseline AC4 at sampling 0.0: nothing is exported, yet the error body still names its trace. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.opentelemetry.tracing.export.otlp.endpoint=" + TracingIT.CLOSED_ENDPOINT,
        "management.tracing.sampling.probability=0.0"
})
@AutoConfigureTracing
@Import({TestcontainersConfig.class, InMemoryTracingConfig.class})
class UnsampledTracingIT {

    @LocalServerPort
    private int port;
    @Autowired
    private JsonMapper json;
    @Autowired
    private InMemorySpanExporter exporter;
    @Autowired
    private SdkTracerProvider tracerProvider;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void ac4UnsampledErrorBodyStillHasTraceId() throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/api/v1/availability?dealershipId=" + UUID.randomUUID()
                + "&serviceTypeId=" + UUID.randomUUID() + "&vehicleTypeId=" + UUID.randomUUID()
                + "&from=" + TracingIT.DAY + "&to=" + TracingIT.DAY);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(json.readTree(response.body()).get("traceId").asString()).matches("^[0-9a-f]{32}$");
        assertThat(InMemoryTracingConfig.flush(tracerProvider, exporter)).isEmpty();
    }
}
