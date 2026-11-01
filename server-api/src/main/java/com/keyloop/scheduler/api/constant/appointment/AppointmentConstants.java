package com.keyloop.scheduler.api.constant.appointment;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Fixed values of the booking path. A booking tries a short list of (bay, technician)
 * pairs, and this caps the list. Spec04 §6, candidate-selection.md.
 *
 * <pre>
 * free bays [B1, B2, B3, B4] × free techs [T1, T2, T3, T4]  →  at most MAX_PAIRS = 3 pairs
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AppointmentConstants {

    /** Spec04 §6: a booking tries at most this many pairs. */
    public static final int MAX_PAIRS = 3;
}
