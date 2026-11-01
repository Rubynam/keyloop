package com.keyloop.scheduler.api.dto.appointment;

import java.time.Instant;
import java.util.UUID;

import com.keyloop.scheduler.api.model.AppointmentStatus;

/**
 * Body of a booked appointment. Times are UTC; {@code endTime} is
 * {@code startTime + service duration}. Spec07 §4.
 *
 * <pre>
 * { "id": A1, "status": "CONFIRMED", "dealershipId": D1, "serviceBayId": B2,
 *   "technicianId": T2, "serviceTypeId": S1, "vehicleId": V1,
 *   "startTime": "2026-11-02T02:00:00Z", "endTime": "2026-11-02T03:00:00Z" }
 * </pre>
 */
public record AppointmentResponse(
        UUID id,
        AppointmentStatus status,
        UUID dealershipId,
        UUID serviceBayId,
        UUID technicianId,
        UUID serviceTypeId,
        UUID vehicleId,
        Instant startTime,
        Instant endTime) {
}
