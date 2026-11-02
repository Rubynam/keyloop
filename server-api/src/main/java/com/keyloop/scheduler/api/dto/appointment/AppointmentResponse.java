package com.keyloop.scheduler.api.dto.appointment;

import java.time.Instant;
import java.util.UUID;

import com.keyloop.scheduler.api.model.AppointmentStatus;

/**
 * Body of a booked appointment. Times are UTC; {@code endTime} is
 * {@code startTime + service duration}. Each id comes with its display name: ids are
 * for the backend, names are for people. Spec07 §4.
 *
 * <pre>
 * { "id": A1, "status": "CONFIRMED",
 *   "dealershipId": D1,  "dealershipName": "Keyloop Hub",
 *   "serviceBayId": B2,  "serviceBayName": "Bay 2",
 *   "technicianId": T2,  "technicianName": "An Nguyen",
 *   "serviceTypeId": S1, "serviceTypeName": "Oil change",
 *   "vehicleId": V1,     "vehicleName": "TOYOTA Vios 2022",
 *   "startTime": "2026-11-02T02:00:00Z", "endTime": "2026-11-02T03:00:00Z" }
 * </pre>
 */
public record AppointmentResponse(
        UUID id,
        AppointmentStatus status,
        UUID dealershipId,
        String dealershipName,
        UUID serviceBayId,
        String serviceBayName,
        UUID technicianId,
        String technicianName,
        UUID serviceTypeId,
        String serviceTypeName,
        UUID vehicleId,
        String vehicleName,
        Instant startTime,
        Instant endTime) {
}
