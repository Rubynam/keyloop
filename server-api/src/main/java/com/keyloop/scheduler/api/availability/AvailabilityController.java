package com.keyloop.scheduler.api.availability;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Free slots for one (dealership, service type, vehicle type) over local dates
 * {@code [from, to]}, at most 14 days (spec-read-availability, DLD §3 / §8).
 * Missing or malformed params are 400; unknown ids are 404 ({@code ApiExceptionHandler}).
 */
@RestController
@RequestMapping("/api/v1")
public class AvailabilityController {

    private final AvailabilityService service;

    public AvailabilityController(AvailabilityService service) {
        this.service = service;
    }

    @GetMapping("/availability")
    public AvailabilityResponse availability(
            @RequestParam UUID dealershipId,
            @RequestParam UUID serviceTypeId,
            @RequestParam UUID vehicleTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.find(dealershipId, serviceTypeId, vehicleTypeId, from, to);
    }
}
