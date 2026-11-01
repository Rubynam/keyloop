package com.keyloop.scheduler.api.model.appointment;

import java.time.Instant;
import java.util.UUID;

import com.keyloop.scheduler.api.model.AppointmentStatus;

/**
 * An appointment row as stored. Used to answer a booking and to compare a replayed
 * request with the first one (Spec07 D3: the 5 request fields must match).
 *
 * <pre>
 * StoredAppointment(A1, C1, V1, D1, OIL, B2, T2, 02:00Z, 03:00Z, CONFIRMED)
 * </pre>
 */
public record StoredAppointment(
        UUID id,
        UUID customerId,
        UUID vehicleId,
        UUID dealershipId,
        UUID serviceTypeId,
        UUID serviceBayId,
        UUID technicianId,
        Instant start,
        Instant end,
        AppointmentStatus status) {}
