package com.keyloop.scheduler.api.dto.appointment;

/**
 * What the booking service hands to the controller: the appointment and whether it was
 * created now or replayed for a known {@code Idempotency-Key}. The flag becomes a
 * header, never a body field.
 *
 * <pre>
 * first call   → BookingResult(A1, false)  → 201
 * same key     → BookingResult(A1, true)   → 201  Idempotent-Replayed: true
 * </pre>
 */
public record BookingResult(AppointmentResponse appointment, boolean replayed) {
}
