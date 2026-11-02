package com.keyloop.scheduler.api.service.appointment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import javax.sql.DataSource;

import com.keyloop.scheduler.api.model.appointment.NewAppointment;
import com.keyloop.scheduler.api.repository.appointment.AppointmentRepository;
import com.keyloop.scheduler.api.repository.appointment.OutboxRepository;
import com.keyloop.scheduler.api.support.Fixtures;
import com.keyloop.scheduler.api.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/appointments} end to end on real PostgreSQL 18 + Redis 8 (Spec07
 * §12 AC3–AC8, AC10–AC12, AC14–AC19). Real HTTP, so parallel calls use their own
 * threads and connections. The repositories are spies: they run the real SQL, and a test
 * can slip a "concurrent" booking in just before an insert. Each test makes its own
 * dealership, so nothing needs cleanup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AppointmentBookingIT extends IntegrationTest {

    /** A Monday far in the future, so "now" never gets in the way. */
    private static final String DAY = "2030-01-07";
    private static final String BOOKING_FAILED = "Booking failed: no technician or service bay is available for the "
            + "requested time. Please choose another slot.";

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private DataSource dataSource;
    @MockitoSpyBean
    private AppointmentRepository appointments;
    @MockitoSpyBean
    private OutboxRepository outbox;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService pool = Executors.newFixedThreadPool(20);
    private Fixtures fixtures;
    private UUID dealer;
    private UUID service;
    private UUID vehicleType;
    private UUID customer;
    private UUID vehicle;

    @BeforeEach
    void seed() {
        fixtures = new Fixtures(jdbc);
        dealer = fixtures.dealership("Asia/Ho_Chi_Minh", "08:00", "17:00");
        service = fixtures.serviceType(60);
        vehicleType = fixtures.vehicleType();
        customer = fixtures.customer();
        vehicle = fixtures.vehicle(customer, vehicleType);
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    // ---------------------------------------------------------------- happy path

    @Test
    void ac14HappyPathWritesOneAppointmentAndOneOutboxEvent() throws Exception {
        UUID bay = fixtures.bay(dealer, vehicleType);
        UUID tech = fixtures.technician(dealer, service, vehicleType);
        String key = key();

        HttpResponse<String> response = book(key, body(customer, vehicle, dealer, service, local("09:00")));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        String id = body.get("id").asString();
        assertThat(response.headers().firstValue("Location")).hasValue("/api/v1/appointments/" + id);
        assertThat(response.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(body.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(body.get("serviceBayId").asString()).isEqualTo(bay.toString());
        assertThat(body.get("technicianId").asString()).isEqualTo(tech.toString());
        // 09:00 in Ho Chi Minh (+07:00) is 02:00 UTC
        assertThat(body.get("startTime").asString()).isEqualTo(DAY + "T02:00:00Z");
        assertThat(body.get("endTime").asString()).isEqualTo(DAY + "T03:00:00Z");

        assertThat(appointmentsWithKey(key)).isEqualTo(1);
        Map<String, Object> event = jdbc.sql("""
                        SELECT aggregate_type, aggregate_id, event_type, payload::text AS payload
                        FROM outbox_events WHERE aggregate_id = ?
                        """)
                .param(id).query().singleRow();
        assertThat(event.get("aggregate_type")).isEqualTo("Appointment");
        assertThat(event.get("event_type")).isEqualTo("AppointmentBooked");
        JsonNode payload = json.readTree((String) event.get("payload"));
        assertThat(payload.get("appointmentId").asString()).isEqualTo(id);
        assertThat(payload.get("dealershipId").asString()).isEqualTo(dealer.toString());
        // Dealership-local date, not UTC
        assertThat(payload.get("dates").get(0).asString()).isEqualTo(DAY);
        assertThat(payload.get("dates").size()).isEqualTo(1);
    }

    @Test
    void ac14PayloadHasTheContractFields() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);

        String id = json.readTree(book(key(), body(customer, vehicle, dealer, service, local("10:00"))).body())
                .get("id").asString();

        JsonNode sample = json.readTree(Files.readString(Path.of("../docs/contracts/outbox/AppointmentBooked.json")));
        JsonNode payload = json.readTree(jdbc.sql("SELECT payload::text FROM outbox_events WHERE aggregate_id = ?")
                .param(id).query(String.class).single());
        assertThat(fieldNames(payload)).isEqualTo(fieldNames(sample));
        for (String field : fieldNames(sample)) {
            assertThat(payload.get(field).getNodeType()).as(field).isEqualTo(sample.get(field).getNodeType());
        }
        assertThat(payload.get("dates").get(0).asString()).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    // ---------------------------------------------------------------- service checks

    @Test
    void ac3UnknownIdsAre404NamingTheEntity() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        UUID unknown = UUID.fromString("00000000-0000-7000-8000-000000000000");
        String start = local("09:00");

        assertProblem(book(key(), body(customer, vehicle, unknown, service, start)), 404,
                "Dealership " + unknown + " not found");
        assertProblem(book(key(), body(customer, vehicle, dealer, unknown, start)), 404,
                "Service type " + unknown + " not found");
        assertProblem(book(key(), body(customer, unknown, dealer, service, start)), 404,
                "Vehicle " + unknown + " not found");
        assertProblem(book(key(), body(unknown, vehicle, dealer, service, start)), 404,
                "Customer " + unknown + " not found");
    }

    @Test
    void ac4VehicleOfAnotherCustomerIs400() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        UUID stranger = fixtures.customer();

        assertProblem(book(key(), body(stranger, vehicle, dealer, service, local("09:00"))), 400,
                "The vehicle does not belong to this customer.");
    }

    @Test
    void ac5WindowOutsideLocalBusinessHoursIs422() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        String outside = "The time is outside the dealership's business hours.";

        // Before open, after close, and inside but ending after close (16:30 + 60 min)
        assertProblem(book(key(), body(customer, vehicle, dealer, service, local("07:00"))), 422, outside);
        assertProblem(book(key(), body(customer, vehicle, dealer, service, local("20:00"))), 422, outside);
        assertProblem(book(key(), body(customer, vehicle, dealer, service, local("16:30"))), 422, outside);
        // 08:00 in UTC terms is 15:00 local: inside. The check is local, not UTC.
        assertThat(book(key(), body(customer, vehicle, dealer, service, DAY + "T08:00:00Z")).statusCode())
                .isEqualTo(201);
        verify(appointments).insert(any());
    }

    @Test
    void ac6VehicleAlreadyBookedIs422AndNoPairIsTried() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        // The same vehicle already holds another bay and technician 09:30–10:30
        UUID otherBay = fixtures.bay(dealer, vehicleType);
        UUID otherTech = fixtures.technician(dealer, service, vehicleType);
        fixtures.appointment(customer, vehicle, dealer, service, vehicleType, otherBay, otherTech,
                at("09:30"), at("10:30"), "CONFIRMED");

        assertProblem(book(key(), body(customer, vehicle, dealer, service, local("09:00"))), 422,
                "The vehicle already has an appointment at this time.");
        verify(appointments, never()).insert(any());
    }

    @Test
    void ac7StartMustBeOnTheSlotGrid() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        String offGrid = "The start time must be on a 15-minute slot.";

        assertProblem(book(key(), body(customer, vehicle, dealer, service, local("09:07"))), 422, offGrid);
        assertProblem(book(key(), body(customer, vehicle, dealer, service, DAY + "T09:00:30+07:00")), 422, offGrid);
        assertThat(book(key(), body(customer, vehicle, dealer, service, local("09:00"))).statusCode()).isEqualTo(201);
    }

    @Test
    void ac7GridUsesLocalTimeOnADstDay() throws Exception {
        // 2030-03-10: New York moves 02:00 → 03:00; open 08:00 local is 12:00Z, not 13:00Z
        UUID ny = fixtures.dealership("America/New_York", "08:00", "17:00");
        fixtures.bay(ny, vehicleType);
        fixtures.technician(ny, service, vehicleType);

        assertProblem(book(key(), body(customer, vehicle, ny, service, "2030-03-10T09:07:00-04:00")), 422,
                "The start time must be on a 15-minute slot.");
        HttpResponse<String> ok = book(key(), body(customer, vehicle, ny, service, "2030-03-10T13:00:00Z"));
        assertThat(ok.statusCode()).isEqualTo(201);
        assertThat(json.readTree(ok.body()).get("startTime").asString()).isEqualTo("2030-03-10T13:00:00Z");
    }

    // ---------------------------------------------------------------- candidates

    @Test
    void ac10NoFittingBayIs422NotServiceable() throws Exception {
        fixtures.bay(dealer, fixtures.vehicleType());
        fixtures.technician(dealer, service, vehicleType);

        assertProblem(book(key(), body(customer, vehicle, dealer, service, local("09:00"))), 422,
                "No bay or technician at this dealership can service this vehicle.");
    }

    @Test
    void ac11AllBusyIs409WithSpec04Text() throws Exception {
        UUID bay = fixtures.bay(dealer, vehicleType);
        UUID tech = fixtures.technician(dealer, service, vehicleType);
        fixtures.appointment(dealer, service, vehicleType, bay, tech, at("09:00"), at("10:00"), "CONFIRMED");

        HttpResponse<String> response = book(key(), body(customer, vehicle, dealer, service, local("09:00")));

        assertProblem(response, 409, BOOKING_FAILED);
        assertThat(json.readTree(response.body()).get("title").asString()).isEqualTo("Booking failed");
    }

    @Test
    void ac12CancelledAndBackToBackDoNotBlock() throws Exception {
        UUID bay = fixtures.bay(dealer, vehicleType);
        UUID tech = fixtures.technician(dealer, service, vehicleType);
        fixtures.appointment(dealer, service, vehicleType, bay, tech, at("08:00"), at("09:00"), "CONFIRMED");
        fixtures.appointment(dealer, service, vehicleType, bay, tech, at("09:00"), at("10:00"), "CANCELLED");

        assertThat(book(key(), body(customer, vehicle, dealer, service, local("09:00"))).statusCode()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- transaction

    @Test
    void ac15FirstPairTakenConcurrentlyThenSecondPairIsBooked() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        List<NewAppointment> tried = new ArrayList<>();
        doAnswer(invocation -> {
            NewAppointment a = invocation.getArgument(0);
            if (tried.isEmpty()) {
                // Another booking commits on its own connection just before our insert
                takeConcurrently(a);
            }
            tried.add(a);
            return invocation.callRealMethod();
        }).when(appointments).insert(any());
        String key = key();

        HttpResponse<String> response = book(key, body(customer, vehicle, dealer, service, local("09:00")));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(tried).hasSize(2);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("serviceBayId").asString()).isEqualTo(tried.get(1).serviceBayId().toString());
        assertThat(body.get("technicianId").asString()).isEqualTo(tried.get(1).technicianId().toString());
        assertThat(appointmentsWithKey(key)).isEqualTo(1);
        assertThat(outboxRowsOfDealer()).isEqualTo(1);
    }

    @Test
    void firstPairLosesItsFitThenSecondPairIsBooked() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        List<NewAppointment> tried = new ArrayList<>();
        doAnswer(invocation -> {
            NewAppointment a = invocation.getArgument(0);
            if (tried.isEmpty()) {
                // After the candidate read, the bay stops taking this vehicle type (committed
                // on another connection): fk_appointments_bay_type refuses our insert
                pool.submit(() -> jdbc.sql("DELETE FROM service_bay_vehicle_types"
                                + " WHERE service_bay_id = ? AND vehicle_type_id = ?")
                        .params(a.serviceBayId(), a.vehicleTypeId()).update()).get();
            }
            tried.add(a);
            return invocation.callRealMethod();
        }).when(appointments).insert(any());
        String key = key();

        HttpResponse<String> response = book(key, body(customer, vehicle, dealer, service, local("09:00")));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(tried).hasSize(2);
        assertThat(tried.get(1).serviceBayId()).isNotEqualTo(tried.get(0).serviceBayId());
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("serviceBayId").asString()).isEqualTo(tried.get(1).serviceBayId().toString());
        assertThat(appointmentsWithKey(key)).isEqualTo(1);
        assertThat(outboxRowsOfDealer()).isEqualTo(1);
    }

    @Test
    void firstPairTechnicianDeactivatedThenSecondPairIsBooked() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        List<NewAppointment> tried = deactivateBeforeFirstInsert("technicians", NewAppointment::technicianId);
        String key = key();

        HttpResponse<String> response = book(key, body(customer, vehicle, dealer, service, local("09:00")));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(tried).hasSize(2);
        assertThat(tried.get(1).technicianId()).isNotEqualTo(tried.get(0).technicianId());
        assertThat(json.readTree(response.body()).get("technicianId").asString())
                .isEqualTo(tried.get(1).technicianId().toString());
        assertThat(appointmentsWithKey(key)).isEqualTo(1);
        assertThat(outboxRowsOfDealer()).isEqualTo(1);
    }

    @Test
    void firstPairBayDeactivatedThenSecondPairIsBooked() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        List<NewAppointment> tried = deactivateBeforeFirstInsert("service_bays", NewAppointment::serviceBayId);
        String key = key();

        HttpResponse<String> response = book(key, body(customer, vehicle, dealer, service, local("09:00")));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(tried).hasSize(2);
        assertThat(tried.get(1).serviceBayId()).isNotEqualTo(tried.get(0).serviceBayId());
        assertThat(json.readTree(response.body()).get("serviceBayId").asString())
                .isEqualTo(tried.get(1).serviceBayId().toString());
        assertThat(appointmentsWithKey(key)).isEqualTo(1);
        assertThat(outboxRowsOfDealer()).isEqualTo(1);
    }

    @Test
    void onlyPairDeactivatedMidFlightIs409AndLeavesNoRows() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        List<NewAppointment> tried = deactivateBeforeFirstInsert("technicians", NewAppointment::technicianId);
        String key = key();

        assertProblem(book(key, body(customer, vehicle, dealer, service, local("09:00"))), 409, BOOKING_FAILED);

        assertThat(tried).hasSize(1);
        assertThat(appointmentsWithKey(key)).isZero();
        assertThat(outboxRowsOfDealer()).isZero();
    }

    @Test
    void deactivationStillOpenDuringInsertIsWaitedForAndSeen() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        List<NewAppointment> tried = new ArrayList<>();
        doAnswer(invocation -> {
            NewAppointment a = invocation.getArgument(0);
            if (tried.isEmpty()) {
                // The deactivation holds its row lock while our insert starts, and commits
                // only later: without FOR SHARE our snapshot would still see active = true
                CountDownLatch updated = new CountDownLatch(1);
                pool.submit(() -> {
                    try (Connection c = dataSource.getConnection()) {
                        c.setAutoCommit(false);
                        try (PreparedStatement s = c.prepareStatement(
                                "UPDATE technicians SET active = false WHERE id = ?")) {
                            s.setObject(1, a.technicianId());
                            s.executeUpdate();
                        }
                        updated.countDown();
                        Thread.sleep(300);
                        c.commit();
                    }
                    return null;
                });
                updated.await();
            }
            tried.add(a);
            return invocation.callRealMethod();
        }).when(appointments).insert(any());
        String key = key();

        HttpResponse<String> response = book(key, body(customer, vehicle, dealer, service, local("09:00")));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(tried).hasSize(2);
        assertThat(json.readTree(response.body()).get("technicianId").asString())
                .isEqualTo(tried.get(1).technicianId().toString());
        assertThat(appointmentsWithKey(key)).isEqualTo(1);
    }

    @Test
    void inactiveTechnicianOrBayIsNeverOffered() throws Exception {
        UUID activeBay = fixtures.bay(dealer, vehicleType);
        UUID inactiveBay = fixtures.bay(dealer, vehicleType);
        UUID activeTech = fixtures.technician(dealer, service, vehicleType);
        UUID inactiveTech = fixtures.technician(dealer, service, vehicleType);
        jdbc.sql("UPDATE service_bays SET active = false WHERE id = ?").param(inactiveBay).update();
        jdbc.sql("UPDATE technicians SET active = false WHERE id = ?").param(inactiveTech).update();
        List<NewAppointment> tried = new ArrayList<>();
        doAnswer(invocation -> {
            tried.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(appointments).insert(any());

        HttpResponse<String> first = book(key(), body(customer, vehicle, dealer, service, local("09:00")));
        UUID owner = fixtures.customer();
        HttpResponse<String> second = book(key(),
                body(owner, fixtures.vehicle(owner, vehicleType), dealer, service, local("09:00")));

        assertThat(first.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(first.body());
        assertThat(body.get("serviceBayId").asString()).isEqualTo(activeBay.toString());
        assertThat(body.get("technicianId").asString()).isEqualTo(activeTech.toString());
        // The only active pair is taken: the inactive ones are not tried, so 409
        assertProblem(second, 409, BOOKING_FAILED);
        assertThat(tried).extracting(NewAppointment::serviceBayId).containsOnly(activeBay);
        assertThat(tried).extracting(NewAppointment::technicianId).containsOnly(activeTech);
    }

    @Test
    void ac16AllPairsFailIs409AndLeavesNoRows() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        List<NewAppointment> tried = new ArrayList<>();
        doAnswer(invocation -> {
            NewAppointment a = invocation.getArgument(0);
            takeConcurrently(a);
            tried.add(a);
            return invocation.callRealMethod();
        }).when(appointments).insert(any());
        String key = key();

        assertProblem(book(key, body(customer, vehicle, dealer, service, local("09:00"))), 409, BOOKING_FAILED);

        assertThat(tried).hasSize(3);
        assertThat(appointmentsWithKey(key)).isZero();
        assertThat(outboxRowsOfDealer()).isZero();
    }

    @Test
    void ac17OutboxFailureRollsBackTheAppointment() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        doThrow(new DataAccessResourceFailureException("outbox down"))
                .when(outbox).append(anyString(), anyString(), anyString(), any());
        String key = key();

        HttpResponse<String> response = book(key, body(customer, vehicle, dealer, service, local("09:00")));

        assertThat(response.statusCode()).isEqualTo(503);
        verify(appointments).insert(any());
        assertThat(appointmentsWithKey(key)).isZero();
        assertThat(outboxRowsOfDealer()).isZero();
    }

    @Test
    void ac18SameKeyOneAfterTheOtherReplaysAndOtherBodyIs422() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        String key = key();
        String body = body(customer, vehicle, dealer, service, local("09:00"));

        HttpResponse<String> first = book(key, body);
        HttpResponse<String> second = book(key, body);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(id(second)).isEqualTo(id(first));
        assertThat(second.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
        assertThat(appointmentsWithKey(key)).isEqualTo(1);
        assertThat(outboxRowsOfDealer()).isEqualTo(1);

        // Same key, other start time
        assertProblem(book(key, body(customer, vehicle, dealer, service, local("10:00"))), 422,
                "This Idempotency-Key was already used for a different request.");
        // Same instant written with another offset is the same request
        assertThat(book(key, body(customer, vehicle, dealer, service, DAY + "T02:00:00Z")).statusCode())
                .isEqualTo(201);
    }

    @Test
    void ac18SameKeyInParallelGivesOneAppointment() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        String key = key();
        String body = body(customer, vehicle, dealer, service, local("09:00"));

        List<HttpResponse<String>> responses = parallel(10, i -> book(key, body));

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(201);
        assertThat(responses.stream().map(this::id).distinct()).hasSize(1);
        assertThat(responses.stream().filter(r -> r.headers().firstValue("Idempotent-Replayed").isEmpty()))
                .hasSize(1);
        assertThat(appointmentsWithKey(key)).isEqualTo(1);
        assertThat(outboxRowsOfDealer()).isEqualTo(1);
    }

    @Test
    void sameVehicleInParallelWithOtherKeysGivesOne201AndThe422s() throws Exception {
        for (int i = 0; i < 3; i++) {
            fixtures.bay(dealer, vehicleType);
            fixtures.technician(dealer, service, vehicleType);
        }
        String body = body(customer, vehicle, dealer, service, local("09:00"));

        List<HttpResponse<String>> responses = parallel(10, i -> book(key(), body));

        Map<Integer, Long> statuses = new LinkedHashMap<>();
        responses.forEach(r -> statuses.merge(r.statusCode(), 1L, Long::sum));
        assertThat(statuses).containsOnly(Map.entry(201, 1L), Map.entry(422, 9L));
        assertThat(outboxRowsOfDealer()).isEqualTo(1);
    }

    @Test
    void ac19TwentyParallelBookingsOnTwoFreePairs() throws Exception {
        fixtures.bay(dealer, vehicleType);
        fixtures.bay(dealer, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        fixtures.technician(dealer, service, vehicleType);
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID owner = fixtures.customer();
            bodies.add(body(owner, fixtures.vehicle(owner, vehicleType), dealer, service, local("09:00")));
        }

        List<HttpResponse<String>> responses = parallel(20, i -> book(key(), bodies.get(i)));

        Map<Integer, Long> statuses = new LinkedHashMap<>();
        responses.forEach(r -> statuses.merge(r.statusCode(), 1L, Long::sum));
        assertThat(statuses).containsOnly(Map.entry(201, 2L), Map.entry(409, 18L));
        // No two live appointments share a bay or a technician in the window
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT service_bay_id, technician_id FROM appointments
                        WHERE dealership_id = ? AND status <> 'CANCELLED'
                        """).param(dealer).query().listOfRows();
        assertThat(rows).hasSize(2);
        Set<Object> bays = new HashSet<>();
        Set<Object> techs = new HashSet<>();
        rows.forEach(r -> {
            bays.add(r.get("service_bay_id"));
            techs.add(r.get("technician_id"));
        });
        assertThat(bays).hasSize(2);
        assertThat(techs).hasSize(2);
        assertThat(outboxRowsOfDealer()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Commits a booking of {@code a}'s bay and technician on another thread and connection.
     * When an earlier concurrent booking already holds one of them, that is enough.
     */
    private void takeConcurrently(NewAppointment a) throws Exception {
        try {
            pool.submit(() -> fixtures.appointment(a.dealershipId(), a.serviceTypeId(), a.vehicleTypeId(),
                    a.serviceBayId(), a.technicianId(), OffsetDateTime.ofInstant(a.start(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(a.end(), ZoneOffset.UTC), "CONFIRMED")).get();
        } catch (ExecutionException alreadyTaken) {
            // ex_appointments_bay / _technician: the pair is blocked anyway
        }
    }

    /**
     * Before the first insert only, sets {@code active = false} on that pair's row in
     * {@code table} (committed on another connection), so the selector's pick is stale.
     */
    private List<NewAppointment> deactivateBeforeFirstInsert(String table, Function<NewAppointment, UUID> idOf) {
        List<NewAppointment> tried = new ArrayList<>();
        doAnswer(invocation -> {
            NewAppointment a = invocation.getArgument(0);
            if (tried.isEmpty()) {
                pool.submit(() -> jdbc.sql("UPDATE " + table + " SET active = false WHERE id = ?")
                        .param(idOf.apply(a)).update()).get();
            }
            tried.add(a);
            return invocation.callRealMethod();
        }).when(appointments).insert(any());
        return tried;
    }

    private interface Call {
        HttpResponse<String> run(int index) throws Exception;
    }

    /** Starts every call at the same moment and waits for all answers. */
    private List<HttpResponse<String>> parallel(int count, Call call) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            Callable<HttpResponse<String>> task = () -> {
                gate.await();
                return call.run(index);
            };
            futures.add(pool.submit(task));
        }
        gate.countDown();
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (Future<HttpResponse<String>> future : futures) {
            responses.add(future.get());
        }
        return responses;
    }

    private HttpResponse<String> book(String key, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/appointments"))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String body(UUID customer, UUID vehicle, UUID dealer, UUID service, String start) {
        return """
                {"customerId":"%s","vehicleId":"%s","dealershipId":"%s","serviceTypeId":"%s","startTime":"%s"}
                """.formatted(customer, vehicle, dealer, service, start);
    }

    private void assertProblem(HttpResponse<String> response, int status, String detail) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).get().asString()
                .startsWith("application/problem+json");
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("detail").asString()).isEqualTo(detail);
    }

    private String id(HttpResponse<String> response) {
        return json.readTree(response.body()).get("id").asString();
    }

    private int appointmentsWithKey(String key) {
        return jdbc.sql("SELECT count(*)::int FROM appointments WHERE idempotency_key = ?")
                .param(key).query(Integer.class).single();
    }

    private int outboxRowsOfDealer() {
        return jdbc.sql("SELECT count(*)::int FROM outbox_events WHERE payload->>'dealershipId' = ?")
                .param(dealer.toString()).query(Integer.class).single();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>(node.propertyNames());
        names.sort(String::compareTo);
        return names;
    }

    /** Local time at the Ho Chi Minh dealership (+07:00, no DST) on {@link #DAY}. */
    private static String local(String time) {
        return DAY + "T" + time + ":00+07:00";
    }

    private static OffsetDateTime at(String localTime) {
        return OffsetDateTime.parse(local(localTime));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
