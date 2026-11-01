package com.keyloop.scheduler.api.model.appointment;

import java.util.List;
import java.util.UUID;

import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;

/**
 * Answer of {@code CandidateSelector}: which (bay, technician) pairs a booking should try,
 * in order, or why there are none (candidate-selection.md).
 *
 * @param outcome why the list is empty, or {@code PAIRS}
 * @param pairs   at most {@value AppointmentConstants#MAX_PAIRS} pairs; empty unless {@code PAIRS}
 */
public record CandidateResult(Outcome outcome, List<Pair> pairs) {

    public enum Outcome {
        /** No active fitting bay or no qualified technician at all: booking answers 422. */
        NOT_SERVICEABLE,
        /** Resources fit, but every bay or every technician is busy in the window: booking answers 409. */
        NONE_FREE,
        /** At least one pair to try. */
        PAIRS
    }

    /** One bay and one technician to try together in one booking transaction. */
    public record Pair(UUID serviceBayId, UUID technicianId) {}

    public static CandidateResult notServiceable() {
        return new CandidateResult(Outcome.NOT_SERVICEABLE, List.of());
    }

    public static CandidateResult noneFree() {
        return new CandidateResult(Outcome.NONE_FREE, List.of());
    }

    public static CandidateResult of(List<Pair> pairs) {
        return new CandidateResult(Outcome.PAIRS, List.copyOf(pairs));
    }
}
