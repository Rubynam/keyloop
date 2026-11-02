package com.keyloop.scheduler.api.dto.availability;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code GET /api/v1/availability}. Every date in {@code [from, to]} is listed,
 * even without slots. Bay and technician ids are deliberately not exposed. Each id comes
 * with its display name, so a client can show it without a second call.
 *
 * <pre>
 * { "dealershipId": D1, "dealershipName": "Keyloop Hub",
 *   "serviceTypeId": S1, "serviceTypeName": "Oil change",
 *   "vehicleTypeId": V1, "vehicleTypeName": "SUV", "timezone": "UTC", ... }
 * </pre>
 */
public record AvailabilityResponse(
        UUID dealershipId,
        String dealershipName,
        UUID serviceTypeId,
        String serviceTypeName,
        UUID vehicleTypeId,
        String vehicleTypeName,
        String timezone,
        int durationMinutes,
        List<Day> days) {

    /** Free windows that start on one local date of the dealership. */
    public record Day(LocalDate date, List<Slot> slots) {}

    /** Half-open window {@code [start, end)}, both with the dealership's UTC offset at that instant. */
    public record Slot(OffsetDateTime start, OffsetDateTime end) {}
}
