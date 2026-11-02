package com.keyloop.scheduler.api.controller.lookup;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.keyloop.scheduler.api.constant.ApiPaths;
import com.keyloop.scheduler.api.dto.lookup.AvailabilityLookupQuery;
import com.keyloop.scheduler.api.dto.lookup.AvailabilityLookupResponse;
import com.keyloop.scheduler.api.service.lookup.LookupService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP edge of the lookups. The availability lookup is the real-time check before a job
 * is confirmed: a service bay AND a qualified technician, both free for the whole window.
 * Missing or malformed params are 400; unknown ids are 404 ({@code ApiExceptionHandler}).
 *
 * <pre>
 * GET /api/v1/lookups/availability?dealershipId=D1
 *     &amp;from=2026-11-03T09:00:00Z&amp;to=2026-11-03T10:00:00Z
 *     [&amp;serviceBayId=B1] [&amp;technicianId=T1] [&amp;serviceTypeId=OIL] [&amp;vehicleTypeId=SUV]
 *
 *   B1 ░░░░░░░░  T1 ░░░░░░░░   → pair (B1, T1)     all ids given = one exact 1-1 check
 * </pre>
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + ApiPaths.LOOKUPS)
public class LookupController {

    private final LookupService service;

    public LookupController(LookupService service) {
        this.service = service;
    }

    /**
     * Free (bay, technician) pairs over {@code [from, to)}, at most 24 hours. Times need an
     * offset ({@code Z} or {@code +07:00}, URL-encoded as {@code %2B07:00}).
     *
     * <pre>
     * 200 { available: true, reasons: [], totalPairs: 1,
     *       pairs: [ { serviceBayId: B1, serviceBayName: "Bay 1", technicianId: T1, technicianName: "An" } ] }
     * </pre>
     */
    @GetMapping(ApiPaths.AVAILABILITY)
    public AvailabilityLookupResponse availability(
            @RequestParam UUID dealershipId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) UUID serviceBayId,
            @RequestParam(required = false) UUID technicianId,
            @RequestParam(required = false) UUID serviceTypeId,
            @RequestParam(required = false) UUID vehicleTypeId) {
        return service.lookup(new AvailabilityLookupQuery(from, to, dealershipId, serviceBayId, technicianId,
                serviceTypeId, vehicleTypeId));
    }
}
