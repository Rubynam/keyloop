package com.keyloop.scheduler.api.dto.availability;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code GET /api/v1/availability}. Every date in {@code [from, to]} is listed,
 * even without slots. The bay is the one the client asked for. Each slot names the
 * technician a booking of that slot would get now (fewest jobs that local day, then id).
 * Each id comes with its display name, so a client can show it without a second call.
 *
 * <pre>
 * { "dealershipId": D1, "dealershipName": "Keyloop Hub",
 *   "serviceBayId": B1, "serviceBayName": "Bay 1",
 *   "serviceTypeId": S1, "serviceTypeName": "Oil change",
 *   "vehicleTypeId": V1, "vehicleTypeName": "SUV", "timezone": "UTC", ...,
 *   "days": [ { "date": "2026-11-02", "slots": [
 *       { "start": "...T09:00Z", "end": "...T10:00Z", "technicianId": T1, "technicianName": "An" } ] } ] }
 * </pre>
 */
public record AvailabilityResponse(
        UUID dealershipId,
        String dealershipName,
        UUID serviceBayId,
        String serviceBayName,
        UUID serviceTypeId,
        String serviceTypeName,
        UUID vehicleTypeId,
        String vehicleTypeName,
        String timezone,
        int durationMinutes,
        List<Day> days) {

    /** Free windows that start on one local date of the dealership. */
    public record Day(LocalDate date, List<Slot> slots) {}

    /**
     * Half-open window {@code [start, end)}, both with the dealership's UTC offset at that
     * instant, and the technician a booking of this window would get.
     */
    public record Slot(OffsetDateTime start, OffsetDateTime end, UUID technicianId, String technicianName) {}
}
