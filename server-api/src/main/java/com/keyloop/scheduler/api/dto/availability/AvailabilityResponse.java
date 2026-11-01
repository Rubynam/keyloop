package com.keyloop.scheduler.api.dto.availability;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code GET /api/v1/availability}. Every date in {@code [from, to]} is listed,
 * even without slots. Bay and technician ids are deliberately not exposed.
 */
public record AvailabilityResponse(
        UUID dealershipId,
        UUID serviceTypeId,
        UUID vehicleTypeId,
        String timezone,
        int durationMinutes,
        List<Day> days) {

    /** Free windows that start on one local date of the dealership. */
    public record Day(LocalDate date, List<Slot> slots) {}

    /** Half-open window {@code [start, end)}, both with the dealership's UTC offset at that instant. */
    public record Slot(OffsetDateTime start, OffsetDateTime end) {}
}
