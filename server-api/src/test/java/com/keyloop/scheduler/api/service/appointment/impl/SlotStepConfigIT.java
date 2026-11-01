package com.keyloop.scheduler.api.service.appointment.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.keyloop.scheduler.api.support.Fixtures;
import com.keyloop.scheduler.api.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spec07 AC8 / D4: one {@code slot-step} for availability and booking. With 30 minutes,
 * availability lists only :00 / :30 starts and a booking at :15 is refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "scheduler.availability.slot-step=30m")
class SlotStepConfigIT extends IntegrationTest {

    private static final String DAY = "2030-01-07";

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void thirtyMinuteStepForAvailabilityAndBooking() throws Exception {
        Fixtures fixtures = new Fixtures(jdbc);
        UUID dealer = fixtures.dealership("UTC", "08:00", "17:00");
        UUID service = fixtures.serviceType(60);
        UUID vehicleType = fixtures.vehicleType();
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        UUID customer = fixtures.customer();
        UUID vehicle = fixtures.vehicle(customer, vehicleType);

        HttpResponse<String> availability = send(HttpRequest.newBuilder(uri("/api/v1/availability?dealershipId="
                + dealer + "&serviceTypeId=" + service + "&vehicleTypeId=" + vehicleType + "&from=" + DAY + "&to=" + DAY))
                .GET());
        assertThat(availability.statusCode()).isEqualTo(200);
        List<String> minutes = new ArrayList<>();
        for (JsonNode slot : json.readTree(availability.body()).get("days").get(0).get("slots")) {
            minutes.add(slot.get("start").asString().substring(14, 16));
        }
        assertThat(minutes).isNotEmpty().containsOnly("00", "30");

        HttpResponse<String> offGrid = book(customer, vehicle, dealer, service, DAY + "T09:15:00Z");
        assertThat(offGrid.statusCode()).isEqualTo(422);
        assertThat(json.readTree(offGrid.body()).get("detail").asString())
                .isEqualTo("The start time must be on a 30-minute slot.");

        assertThat(book(customer, vehicle, dealer, service, DAY + "T09:30:00Z").statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> book(UUID customer, UUID vehicle, UUID dealer, UUID service, String start)
            throws Exception {
        String body = """
                {"customerId":"%s","vehicleId":"%s","dealershipId":"%s","serviceTypeId":"%s","startTime":"%s"}
                """.formatted(customer, vehicle, dealer, service, start);
        return send(HttpRequest.newBuilder(uri("/api/v1/appointments"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
