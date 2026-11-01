package com.keyloop.scheduler.api.config.appointment;

import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Limits of the booking write path. A booking tries a short list of (bay, technician)
 * pairs, one transaction each. When all were taken meanwhile it reads the candidates
 * again, and answers 409 once no pair is free. Spec04 §6, Spec07 §9.
 *
 * <pre>
 * scheduler.booking.max-pairs: 3
 *   free bays [B1, B2, B3, B4] × free techs [T1, T2, T3, T4]  →  at most 3 pairs per read
 * scheduler.booking.max-candidate-reads: 10
 *   read 1: (B1,T1) (B2,T2) (B3,T3) all taken → read 2: (B4,T4) → 201
 * </pre>
 *
 * @param maxPairs           most pairs tried per candidate read
 * @param maxCandidateReads  most candidate reads per booking; a guard only, since each
 *                           lost read means other bookings filled pairs
 */
@Validated
@ConfigurationProperties("scheduler.booking")
public record BookingProperties(@Positive @DefaultValue("3") int maxPairs,
                                @Positive @DefaultValue("10") int maxCandidateReads) {
}
