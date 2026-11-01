package com.keyloop.scheduler.api.controller.availability;

import java.time.LocalDate;
import java.util.UUID;

import com.keyloop.scheduler.api.constant.ApiPaths;
import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse;
import com.keyloop.scheduler.api.service.availability.AvailabilityService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP edge of the availability read. Customers call it to see free slots before they
 * book. Missing or malformed params are 400; unknown ids are 404 ({@code ApiExceptionHandler}).
 * spec-read-availability, DLD §3 / §8.
 *
 * <pre>
 * GET /api/v1/availability?dealershipId=D1&amp;serviceTypeId=S1&amp;vehicleTypeId=V1
 *     &amp;from=2026-11-02&amp;to=2026-11-03
 *
 * 2026-11-02  09:00 ──[bay B1 / tech T1]── 10:00   free
 *             10:00 ──[booked]──────────── 11:00   x
 * 2026-11-03  09:00 ──[bay B2 / tech T3]── 10:00   free
 * </pre>
 */
@RestController
@RequestMapping(ApiPaths.API_V1)
public class AvailabilityController {

    private final AvailabilityService service;

    public AvailabilityController(AvailabilityService service) {
        this.service = service;
    }

    /**
     * Free slots for one (dealership, service type, vehicle type) over local dates
     * {@code [from, to]}, at most 14 days.
     *
     * <pre>
     * 200 { dealershipId: D1, ..., timezone: UTC, durationMinutes: 60,
     *       days: [ { date: 2026-11-02, slots: [ { start: 09:00Z, end: 10:00Z } ] } ] }
     * </pre>
     */
    @GetMapping(ApiPaths.AVAILABILITY)
    public AvailabilityResponse availability(
            @RequestParam UUID dealershipId,
            @RequestParam UUID serviceTypeId,
            @RequestParam UUID vehicleTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.find(dealershipId, serviceTypeId, vehicleTypeId, from, to);
    }
}
