package com.keyloop.scheduler.api.dto.appointment;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/v1/appointments}. The client picks the time; the server
 * picks the bay and the technician. {@code startTime} must carry an offset, and
 * {@code @Future} reads the app {@code Clock} (Spec07 D5).
 *
 * <pre>
 * { "customerId": C1, "vehicleId": V1, "dealershipId": D1, "serviceTypeId": S1,
 *   "startTime": "2026-11-02T09:00:00+07:00" }
 * </pre>
 */
public record BookAppointmentRequest(
        @NotNull UUID customerId,
        @NotNull UUID vehicleId,
        @NotNull UUID dealershipId,
        @NotNull UUID serviceTypeId,
        @NotNull @Future OffsetDateTime startTime) {
}
