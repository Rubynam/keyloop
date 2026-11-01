package com.keyloop.scheduler.api.model.availability;

import java.time.Instant;

/** Half-open busy interval {@code [start, end)} of one bay or technician. */
public record Interval(Instant start, Instant end) {

    /** True when {@code [otherStart, otherEnd)} shares at least one instant with this interval. */
    public boolean overlaps(Instant otherStart, Instant otherEnd) {
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }
}
