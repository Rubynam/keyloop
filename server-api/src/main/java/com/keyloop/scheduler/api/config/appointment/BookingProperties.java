package com.keyloop.scheduler.api.config.appointment;

import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Limits of the booking write path. A booking tries a short list of (bay, technician)
 * pairs, one transaction each, and answers 409 when all of them fail. Spec04 §6, Spec07 §9.
 *
 * <pre>
 * scheduler.booking.max-pairs: 3
 *   free bays [B1, B2, B3, B4] × free techs [T1, T2, T3, T4]  →  at most 3 pairs tried
 * </pre>
 *
 * @param maxPairs most pairs one booking tries before it gives up
 */
@Validated
@ConfigurationProperties("scheduler.booking")
public record BookingProperties(@Positive @DefaultValue("3") int maxPairs) {
}
