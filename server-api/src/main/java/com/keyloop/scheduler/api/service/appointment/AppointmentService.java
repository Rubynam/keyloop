package com.keyloop.scheduler.api.service.appointment;

import com.keyloop.scheduler.api.dto.appointment.BookAppointmentRequest;
import com.keyloop.scheduler.api.dto.appointment.BookingResult;

/**
 * Books one service bay and one qualified technician for a customer's vehicle at the
 * time they picked. PostgreSQL constraints are the only judge of double booking; this
 * service checks the request, picks good pairs and tries them one by one. Spec07 §5–§7.
 *
 * <pre>
 * book(k1, {C1, V1, D1, OIL, 09:00+07:00})
 *   key k1 known?          yes, same body → old A1 (replayed) / other body → 422
 *   ids, owner, grid, hours, vehicle free?                    → 404 / 400 / 422
 *   pairs (B2,T2) (B1,T1) (B2,T1)
 *     try (B2,T2) ── 23P01 bay taken ──► try (B1,T1) ── COMMIT ──► A1 (new)
 *   all pairs fail                                            → 409
 * </pre>
 */
public interface AppointmentService {

    /**
     * Books the request, or returns the appointment already stored for this key.
     *
     * <pre>
     * (k1, req) → BookingResult(A1 {B1, T1, 02:00Z–03:00Z, CONFIRMED}, replayed = false)
     * (k1, req) → BookingResult(A1 ..., replayed = true)
     * </pre>
     *
     * @param idempotencyKey client key; the same key never creates a second appointment
     */
    BookingResult book(String idempotencyKey, BookAppointmentRequest request);
}
