package com.keyloop.scheduler.api.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Names written to {@code outbox_events}. The scheduler-batch relay reads the same
 * names and payload contract ({@code docs/contracts/outbox/*.json}), so do not change a
 * character. Spec04 §4.4, Spec07 D7.
 *
 * <pre>
 * aggregate_type = APPOINTMENT          "Appointment"
 * event_type     = APPOINTMENT_BOOKED   "AppointmentBooked"
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OutboxEventTypes {

    public static final String APPOINTMENT = "Appointment";
    public static final String APPOINTMENT_BOOKED = "AppointmentBooked";
}
