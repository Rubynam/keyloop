package com.keyloop.scheduler.api.availability;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.keyloop.scheduler.api.availability.AvailabilityResponse.Day;
import com.keyloop.scheduler.api.availability.AvailabilityResponse.Slot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(AvailabilityController.class)
class AvailabilityControllerTest {

    private static final UUID DEALER = UUID.fromString("01942976-3400-790c-bca3-04171fb17c23");
    private static final UUID SERVICE = UUID.fromString("01941f97-5ce8-7aed-ac5f-7495f4c18226");
    private static final UUID VEHICLE = UUID.fromString("01941f29-7fe8-752e-89a7-834df2a74de4");
    private static final LocalDate FROM = LocalDate.of(2026, 10, 31);
    private static final LocalDate TO = LocalDate.of(2026, 11, 1);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AvailabilityService service;

    private static MockHttpServletRequestBuilder request(String from, String to) {
        return get("/api/v1/availability")
                .param("dealershipId", DEALER.toString())
                .param("serviceTypeId", SERVICE.toString())
                .param("vehicleTypeId", VEHICLE.toString())
                .param("from", from)
                .param("to", to);
    }

    @Test
    void returnsDaysWithOffsetSlots() throws Exception {
        when(service.find(DEALER, SERVICE, VEHICLE, FROM, TO)).thenReturn(new AvailabilityResponse(
                DEALER, SERVICE, VEHICLE, "America/New_York", 60, List.of(
                        new Day(FROM, List.of(new Slot(OffsetDateTime.parse("2026-10-31T07:00-04:00"),
                                OffsetDateTime.parse("2026-10-31T08:00-04:00")))),
                        new Day(TO, List.of()))));

        mockMvc.perform(request("2026-10-31", "2026-11-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dealershipId").value(DEALER.toString()))
                .andExpect(jsonPath("$.serviceTypeId").value(SERVICE.toString()))
                .andExpect(jsonPath("$.vehicleTypeId").value(VEHICLE.toString()))
                .andExpect(jsonPath("$.timezone").value("America/New_York"))
                .andExpect(jsonPath("$.durationMinutes").value(60))
                .andExpect(jsonPath("$.days.length()").value(2))
                .andExpect(jsonPath("$.days[0].date").value("2026-10-31"))
                .andExpect(jsonPath("$.days[0].slots[0].start").value("2026-10-31T07:00:00-04:00"))
                .andExpect(jsonPath("$.days[0].slots[0].end").value("2026-10-31T08:00:00-04:00"))
                .andExpect(jsonPath("$.days[1].date").value("2026-11-01"))
                .andExpect(jsonPath("$.days[1].slots.length()").value(0))
                .andExpect(content().string(not(containsString("bay"))))
                .andExpect(content().string(not(containsString("technician"))));
    }

    @Test
    void unknownIdIs404Problem() throws Exception {
        when(service.find(any(), any(), any(), any(), any()))
                .thenThrow(new NotFoundException("Dealership", DEALER));

        mockMvc.perform(request("2026-10-31", "2026-11-01"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value(containsString(DEALER.toString())));
    }

    @Test
    void invalidRangeIs400Problem() throws Exception {
        when(service.find(eq(DEALER), eq(SERVICE), eq(VEHICLE), any(), any()))
                .thenThrow(new InvalidRangeException("'to' is before 'from'"));

        mockMvc.perform(request("2026-11-02", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void missingParamIs400Problem() throws Exception {
        mockMvc.perform(get("/api/v1/availability")
                        .param("dealershipId", DEALER.toString())
                        .param("serviceTypeId", SERVICE.toString())
                        .param("from", "2026-10-31")
                        .param("to", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(containsString("vehicleTypeId")));
        verifyNoInteractions(service);
    }

    @Test
    void malformedDateIs400Problem() throws Exception {
        mockMvc.perform(request("abc", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void malformedUuidIs400Problem() throws Exception {
        mockMvc.perform(get("/api/v1/availability")
                        .param("dealershipId", "not-a-uuid")
                        .param("serviceTypeId", SERVICE.toString())
                        .param("vehicleTypeId", VEHICLE.toString())
                        .param("from", "2026-10-31")
                        .param("to", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void databaseErrorIs503ProblemWithoutInternals() throws Exception {
        when(service.find(any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("connection to 10.0.0.5 refused"));

        mockMvc.perform(request("2026-10-31", "2026-11-01"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(content().string(not(containsString("10.0.0.5"))));
    }

    @Test
    void unexpectedErrorIs500ProblemWithoutInternals() throws Exception {
        when(service.find(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("secret internal state"));

        mockMvc.perform(request("2026-10-31", "2026-11-01"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(content().string(not(containsString("secret"))));
    }
}
