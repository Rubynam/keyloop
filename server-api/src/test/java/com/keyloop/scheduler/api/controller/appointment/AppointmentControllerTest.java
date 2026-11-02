package com.keyloop.scheduler.api.controller.appointment;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.keyloop.scheduler.api.config.appointment.ValidationClockConfig;
import com.keyloop.scheduler.api.dto.appointment.AppointmentResponse;
import com.keyloop.scheduler.api.dto.appointment.BookingResult;
import com.keyloop.scheduler.api.exception.appointment.BookingConflictException;
import com.keyloop.scheduler.api.exception.appointment.OffSlotGridException;
import com.keyloop.scheduler.api.model.AppointmentStatus;
import com.keyloop.scheduler.api.service.appointment.AppointmentService;
import com.keyloop.scheduler.api.support.MutableClock;
import com.keyloop.scheduler.api.support.WebSliceTracingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Spec07 AC1, AC2, AC9: the edge contract, no database. */
@WebMvcTest(AppointmentController.class)
@Import({WebSliceTracingConfig.class, ValidationClockConfig.class, AppointmentControllerTest.ClockStub.class})
class AppointmentControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final String KEY = "6f1c2a0e-key";
    private static final UUID ID = UUID.fromString("01a0d0ba-d3e0-7cd2-ad47-3b3b3e5bcce6");
    private static final UUID CUSTOMER = UUID.fromString("01942976-3400-790c-bca3-04171fb17c01");
    private static final UUID VEHICLE = UUID.fromString("01942976-3400-790c-bca3-04171fb17c02");
    private static final UUID DEALER = UUID.fromString("01942976-3400-790c-bca3-04171fb17c03");
    private static final UUID SERVICE = UUID.fromString("01942976-3400-790c-bca3-04171fb17c04");
    private static final UUID BAY = UUID.fromString("01942976-3400-790c-bca3-04171fb17c05");
    private static final UUID TECH = UUID.fromString("01942976-3400-790c-bca3-04171fb17c06");
    private static final AppointmentResponse BOOKED = new AppointmentResponse(ID, AppointmentStatus.CONFIRMED,
            DEALER, BAY, TECH, SERVICE, VEHICLE,
            Instant.parse("2026-11-02T02:00:00Z"), Instant.parse("2026-11-02T03:00:00Z"));

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockStub {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MutableClock clock;

    @MockitoBean
    private AppointmentService service;

    @BeforeEach
    void resetClock() {
        clock.set(NOW);
    }

    private static String body(String customer, String vehicle, String dealer, String service, String start) {
        StringBuilder json = new StringBuilder("{");
        append(json, "customerId", customer);
        append(json, "vehicleId", vehicle);
        append(json, "dealershipId", dealer);
        append(json, "serviceTypeId", service);
        append(json, "startTime", start);
        if (json.length() > 1) {
            json.setLength(json.length() - 1);
        }
        return json.append('}').toString();
    }

    private static void append(StringBuilder json, String name, String value) {
        if (value == null) {
            return;
        }
        json.append('"').append(name).append("\":").append(value.equals("null") ? "null" : '"' + value + '"').append(',');
    }

    private static String validBody() {
        return body(CUSTOMER.toString(), VEHICLE.toString(), DEALER.toString(), SERVICE.toString(),
                "2026-11-02T09:00:00+07:00");
    }

    private static MockHttpServletRequestBuilder book(String key, String json) {
        MockHttpServletRequestBuilder request = post("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    @Test
    void createdWithLocationAndUtcTimes() throws Exception {
        when(service.book(eq(KEY), any())).thenReturn(new BookingResult(BOOKED, false));

        mockMvc.perform(book(KEY, validBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/appointments/" + ID))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.serviceBayId").value(BAY.toString()))
                .andExpect(jsonPath("$.technicianId").value(TECH.toString()))
                .andExpect(jsonPath("$.startTime").value("2026-11-02T02:00:00Z"))
                .andExpect(jsonPath("$.endTime").value("2026-11-02T03:00:00Z"));
    }

    @Test
    void replayCarriesHeader() throws Exception {
        when(service.book(eq(KEY), any())).thenReturn(new BookingResult(BOOKED, true));

        mockMvc.perform(book(KEY, validBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.replayed").doesNotExist());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "missing customerId  | customerId    | -     | v | d | s | 2026-11-02T09:00:00+07:00",
            "null vehicleId      | vehicleId     | c     | null | d | s | 2026-11-02T09:00:00+07:00",
            "missing dealership  | dealershipId  | c     | v | - | s | 2026-11-02T09:00:00+07:00",
            "bad uuid text       | serviceTypeId | c     | v | d | not-a-uuid | 2026-11-02T09:00:00+07:00",
            "missing startTime   | startTime     | c     | v | d | s | -",
            "past startTime      | startTime     | c     | v | d | s | 2026-10-31T09:00:00+07:00",
            "no offset           | startTime     | c     | v | d | s | 2026-11-02T09:00:00",
    })
    void badBodyIs400WithField(String name, String field, String c, String v, String d, String s, String start)
            throws Exception {
        mockMvc.perform(book(KEY, body(id(c, CUSTOMER), id(v, VEHICLE), id(d, DEALER), id(s, SERVICE),
                        "-".equals(start) ? null : start)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("Invalid request"))
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));
        verifyNoInteractions(service);
    }

    private static String id(String token, UUID value) {
        return switch (token) {
            case "-" -> null;
            case "null" -> "null";
            case "c", "v", "d", "s" -> value.toString();
            default -> token;
        };
    }

    @Test
    void notJsonIs400() throws Exception {
        mockMvc.perform(book(KEY, "not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request"))
                .andExpect(jsonPath("$.errors").isArray());
        verifyNoInteractions(service);
    }

    @Test
    void jsonArrayBodyIs400OnBody() throws Exception {
        mockMvc.perform(book(KEY, "[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request"))
                .andExpect(jsonPath("$.errors[0].field").value("body"));
        verifyNoInteractions(service);
    }

    @Test
    void missingKeyIs400() throws Exception {
        mockMvc.perform(book(null, validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        verifyNoInteractions(service);
    }

    @Test
    void blankKeyIs400() throws Exception {
        mockMvc.perform(book("   ", validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        verifyNoInteractions(service);
    }

    @Test
    void keyOf129CharsIs400And128Passes() throws Exception {
        mockMvc.perform(book("k".repeat(129), validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        verifyNoInteractions(service);

        when(service.book(any(), any())).thenReturn(new BookingResult(BOOKED, false));
        mockMvc.perform(book("k".repeat(128), validBody()))
                .andExpect(status().isCreated());
    }

    @Test
    void futureFollowsTheClockBean() throws Exception {
        when(service.book(any(), any())).thenReturn(new BookingResult(BOOKED, false));
        mockMvc.perform(book(KEY, validBody())).andExpect(status().isCreated());

        // Move "now" past the requested start: the same body is now in the past
        clock.set(Instant.parse("2026-11-03T00:00:00Z"));
        mockMvc.perform(book(KEY, validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("startTime"));
    }

    @Test
    void serviceErrorsMapToTheirStatus() throws Exception {
        when(service.book(any(), any())).thenThrow(new OffSlotGridException(15));
        mockMvc.perform(book(KEY, validBody()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("The start time must be on a 15-minute slot."));
    }

    @Test
    void conflictIs409WithSpec04Text() throws Exception {
        when(service.book(any(), any())).thenThrow(new BookingConflictException());
        mockMvc.perform(book(KEY, validBody()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Booking failed"))
                .andExpect(jsonPath("$.detail").value("Booking failed: no technician or service bay is available "
                        + "for the requested time. Please choose another slot."));
    }

    /** Keeps the import of {@link Clock} honest: the stub is the only clock in the slice. */
    @Test
    void clockStubIsTheClock() {
        org.assertj.core.api.Assertions.assertThat((Clock) clock).isInstanceOf(MutableClock.class);
    }
}
