package com.keyloop.scheduler.api.model.appointment;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * JSON payload of the {@code AppointmentBooked} outbox event. The relay bumps the cache
 * version of every listed dealership-local date. Field names are the contract in
 * {@code docs/contracts/outbox/AppointmentBooked.json} (Spec04 §4.4).
 *
 * <pre>
 * { "appointmentId": A1, "dealershipId": D1, "dates": ["2026-11-02"] }
 * window 23:30–00:30 local  →  dates ["2026-11-02", "2026-11-03"]
 * </pre>
 */
public record AppointmentBookedPayload(UUID appointmentId, UUID dealershipId, List<LocalDate> dates) {}
