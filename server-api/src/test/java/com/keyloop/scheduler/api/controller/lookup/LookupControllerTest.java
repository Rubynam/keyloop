package com.keyloop.scheduler.api.controller.lookup;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.keyloop.scheduler.api.dto.lookup.AvailabilityLookupQuery;
import com.keyloop.scheduler.api.dto.lookup.AvailabilityLookupResponse;
import com.keyloop.scheduler.api.dto.lookup.AvailabilityLookupResponse.Pair;
import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.exception.lookup.InvalidWindowException;
import com.keyloop.scheduler.api.service.lookup.LookupService;
import com.keyloop.scheduler.api.support.WebSliceTracingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(LookupController.class)
@Import(WebSliceTracingConfig.class)
class LookupControllerTest {

    private static final UUID DEALER = UUID.fromString("01942976-3400-790c-bca3-04171fb17c23");
    private static final UUID SERVICE = UUID.fromString("01941f97-5ce8-7aed-ac5f-7495f4c18226");
    private static final UUID VEHICLE = UUID.fromString("01941f29-7fe8-752e-89a7-834df2a74de4");
    private static final UUID BAY = UUID.fromString("01942976-3400-7d1e-a1b2-000000000b01");
    private static final UUID TECH = UUID.fromString("01942976-3400-7d1e-a1b2-000000000701");
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-11-03T09:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-11-03T10:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LookupService service;

    private static MockHttpServletRequestBuilder request() {
        return get("/api/v1/lookups/availability")
                .param("dealershipId", DEALER.toString())
                .param("from", "2026-11-03T09:00:00Z")
                .param("to", "2026-11-03T10:00:00Z");
    }

    @Test
    void returnsFreePairs() throws Exception {
        when(service.lookup(new AvailabilityLookupQuery(FROM, TO, DEALER, null, null, null, null)))
                .thenReturn(new AvailabilityLookupResponse(true,
                        OffsetDateTime.parse("2026-11-03T16:00+07:00"), OffsetDateTime.parse("2026-11-03T17:00+07:00"),
                        DEALER, "Hub", "Asia/Ho_Chi_Minh", null, null, null, null, List.of(), 1,
                        List.of(new Pair(BAY, "Bay 1", TECH, "An"))));

        mockMvc.perform(request())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.from").value("2026-11-03T16:00:00+07:00"))
                .andExpect(jsonPath("$.to").value("2026-11-03T17:00:00+07:00"))
                .andExpect(jsonPath("$.dealershipId").value(DEALER.toString()))
                .andExpect(jsonPath("$.dealershipName").value("Hub"))
                .andExpect(jsonPath("$.reasons.length()").value(0))
                .andExpect(jsonPath("$.totalPairs").value(1))
                .andExpect(jsonPath("$.pairs[0].serviceBayId").value(BAY.toString()))
                .andExpect(jsonPath("$.pairs[0].serviceBayName").value("Bay 1"))
                .andExpect(jsonPath("$.pairs[0].technicianId").value(TECH.toString()))
                .andExpect(jsonPath("$.pairs[0].technicianName").value("An"));
    }

    @Test
    void passesOptionalIdsThrough() throws Exception {
        when(service.lookup(new AvailabilityLookupQuery(FROM, TO, DEALER, BAY, TECH, SERVICE, VEHICLE)))
                .thenReturn(new AvailabilityLookupResponse(false, FROM, TO, DEALER, "Hub", "UTC",
                        SERVICE, "Oil change", VEHICLE, "SUV", List.of(), 0, List.of()));

        mockMvc.perform(request()
                        .param("serviceBayId", BAY.toString())
                        .param("technicianId", TECH.toString())
                        .param("serviceTypeId", SERVICE.toString())
                        .param("vehicleTypeId", VEHICLE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.serviceTypeName").value("Oil change"))
                .andExpect(jsonPath("$.vehicleTypeName").value("SUV"))
                .andExpect(jsonPath("$.pairs.length()").value(0));
    }

    @Test
    void missingDealershipIs400Problem() throws Exception {
        mockMvc.perform(get("/api/v1/lookups/availability")
                        .param("from", "2026-11-03T09:00:00Z")
                        .param("to", "2026-11-03T10:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("dealershipId")))
                .andExpect(jsonPath("$.traceId").value(WebSliceTracingConfig.TRACE_ID));
        verifyNoInteractions(service);
    }

    @Test
    void timeWithoutOffsetIs400Problem() throws Exception {
        mockMvc.perform(get("/api/v1/lookups/availability")
                        .param("dealershipId", DEALER.toString())
                        .param("from", "2026-11-03T09:00")
                        .param("to", "2026-11-03T10:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service);
    }

    @Test
    void malformedOptionalUuidIs400Problem() throws Exception {
        mockMvc.perform(request().param("technicianId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service);
    }

    @Test
    void invalidWindowIs400Problem() throws Exception {
        when(service.lookup(any())).thenThrow(new InvalidWindowException("'to' must be after 'from'"));

        mockMvc.perform(request())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("'to' must be after 'from'"))
                .andExpect(jsonPath("$.traceId").value(WebSliceTracingConfig.TRACE_ID));
    }

    @Test
    void unknownIdIs404Problem() throws Exception {
        when(service.lookup(any())).thenThrow(new NotFoundException("Service bay", BAY));

        mockMvc.perform(request())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Service bay " + BAY + " not found"))
                .andExpect(jsonPath("$.traceId").value(WebSliceTracingConfig.TRACE_ID));
    }
}
