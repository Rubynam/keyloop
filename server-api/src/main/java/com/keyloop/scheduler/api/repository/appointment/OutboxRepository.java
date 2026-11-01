package com.keyloop.scheduler.api.repository.appointment;

/**
 * Writes outbox events in the caller's transaction, so an event exists if and only if
 * its appointment change committed. scheduler-batch reads them later and bumps the
 * availability cache versions. Spec04 §4, Spec07 §7.2.
 *
 * <pre>
 * BEGIN
 *   INSERT appointments ...                               (AppointmentRepository)
 *   append("Appointment", A1, "AppointmentBooked", {appointmentId, dealershipId, dates})
 * COMMIT ──► relay: INCR avail:ver:{D1}:{2026-11-02}
 * </pre>
 */
public interface OutboxRepository {

    /**
     * Adds one pending event. The payload is written as JSON.
     *
     * <pre>
     * append("Appointment", "A1", "AppointmentBooked", payload) → 1 row in outbox_events
     * </pre>
     */
    void append(String aggregateType, String aggregateId, String eventType, Object payload);
}
