package com.keyloop.scheduler.api.cache.availability.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.keyloop.scheduler.api.constant.availability.AvailabilityConstants;
import com.keyloop.scheduler.api.support.Fixtures;
import com.keyloop.scheduler.api.support.IntegrationTest;
import com.keyloop.scheduler.api.support.LogCapture;
import com.redis.testcontainers.RedisContainer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * spec-availability-ops CAP-4: Redis down → 200 from PostgreSQL with a WARN line and a
 * span event; then PostgreSQL down too → 503. Stops the containers of its own context,
 * which is thrown away afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Fail fast once PostgreSQL is gone (default is 30 s)
        properties = "spring.datasource.hikari.connection-timeout=1000")
@Import(AvailabilityFailOpenIT.EventsConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AvailabilityFailOpenIT extends IntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2030, 1, 7);

    /** Records every observation event: what a tracer turns into span events. */
    static class EventRecorder implements ObservationHandler<Observation.Context> {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(Observation.Event event, Observation.Context context) {
            events.add(event.getName());
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventsConfig {
        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private RedisContainer redisContainer;
    @Autowired
    private PostgreSQLContainer postgresContainer;
    @Autowired
    private EventRecorder events;

    private final HttpClient http = HttpClient.newHttpClient();

    private static UUID dealer;
    private static UUID service;
    private static UUID vehicleType;

    @Test
    @Order(1)
    void redisDownStillAnswersFromPostgres() throws Exception {
        Fixtures fixtures = new Fixtures(jdbc);
        dealer = fixtures.dealership("UTC", "08:00", "17:00");
        vehicleType = fixtures.vehicleType();
        service = fixtures.serviceType(60);
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);

        redisContainer.stop();

        try (LogCapture log = LogCapture.of(RedisAvailabilityCache.class)) {
            HttpResponse<String> response = get();

            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).contains("\"start\":\"2030-01-07T08:00:00Z\"");
            assertThat(log.lines()).anySatisfy(line ->
                    assertThat(line).startsWith("WARN Availability cache unavailable, reading from PostgreSQL"));
        }
        assertThat(events.events).contains(AvailabilityConstants.FAIL_OPEN_EVENT);
    }

    @Test
    @Order(2)
    void postgresDownAnswers503() throws Exception {
        postgresContainer.stop();

        HttpResponse<String> response = get();

        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(response.body()).contains("Service Unavailable");
    }

    private HttpResponse<String> get() throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/api/v1/availability?dealershipId=" + dealer
                + "&serviceTypeId=" + service + "&vehicleTypeId=" + vehicleType + "&from=" + DAY + "&to=" + DAY);
        return http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
