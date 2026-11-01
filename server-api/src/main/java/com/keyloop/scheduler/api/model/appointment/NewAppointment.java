package com.keyloop.scheduler.api.model.appointment;

import java.time.Instant;
import java.util.UUID;

/**
 * One appointment row to insert for one (bay, technician) try. The status is always
 * {@code CONFIRMED} on insert. Spec07 §7.2.
 *
 * <pre>
 * NewAppointment(C1, V1, D1, OIL, SUV, B2, T2, 02:00Z, 03:00Z, "k1")
 * </pre>
 */
public record NewAppointment(
        UUID customerId,
        UUID vehicleId,
        UUID dealershipId,
        UUID serviceTypeId,
        UUID vehicleTypeId,
        UUID serviceBayId,
        UUID technicianId,
        Instant start,
        Instant end,
        String idempotencyKey) {}
