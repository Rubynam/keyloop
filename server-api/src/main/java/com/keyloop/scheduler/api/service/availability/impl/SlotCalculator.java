package com.keyloop.scheduler.api.service.availability.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.keyloop.scheduler.api.constant.availability.AvailabilityConstants;
import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse.Day;
import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse.Slot;
import com.keyloop.scheduler.api.model.availability.Interval;

/**
 * Pure free-slot computation (DLD §8, spec-read-availability). No I/O: everything it
 * needs is passed in, so every edge case is unit-testable without a database.
 *
 * <ul>
 *   <li>Candidate starts every {@value AvailabilityConstants#STEP_MINUTES} min of local wall-clock time from
 *       {@code open}; a local time skipped by a DST gap is not a candidate, a local time
 *       repeated by a DST overlap is a candidate once per offset.</li>
 *   <li>Window {@code [start, start + duration)} must end at or before local {@code close}
 *       ({@code 1440} = end of day).</li>
 *   <li>The now-cut is not part of {@link #compute}: the full day is what the cache
 *       stores, and {@link #notStartedBefore} drops past starts per request.</li>
 *   <li>A window is free when at least one bay AND at least one technician have no busy
 *       interval overlapping it ({@code a.start < b.end && b.start < a.end}). Bays and
 *       technicians are independent: the DB fit checks are per resource, not per pair.</li>
 * </ul>
 */
public final class SlotCalculator {

    private SlotCalculator() {}

    /**
     * @param bays        one entry per fitting bay: its busy intervals (empty = free all day)
     * @param technicians one entry per qualified technician: its busy intervals
     * @return one {@link Day} per local date in {@code [from, to]}, slots ordered by start
     */
    public static List<Day> compute(LocalDate from, LocalDate to, ZoneId zone,
                                    int openMinute, int closeMinute, int durationMinutes,
                                    Collection<? extends Collection<Interval>> bays,
                                    Collection<? extends Collection<Interval>> technicians) {
        ZoneRules rules = zone.getRules();
        Duration duration = Duration.ofMinutes(durationMinutes);
        List<Day> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<Slot> slots = new ArrayList<>();
            if (!bays.isEmpty() && !technicians.isEmpty()) {
                LocalDateTime midnight = date.atStartOfDay();
                Instant close = ZonedDateTime.ofLocal(midnight.plusMinutes(closeMinute), zone, null)
                        .withLaterOffsetAtOverlap().toInstant();
                for (int m = openMinute; m < closeMinute; m += AvailabilityConstants.STEP_MINUTES) {
                    LocalDateTime local = midnight.plusMinutes(m);
                    // Empty in a DST gap, two offsets in a DST overlap
                    for (ZoneOffset offset : rules.getValidOffsets(local)) {
                        Instant start = local.toInstant(offset);
                        Instant end = start.plus(duration);
                        if (end.isAfter(close)) {
                            continue;
                        }
                        if (anyFree(bays, start, end) && anyFree(technicians, start, end)) {
                            slots.add(new Slot(start.atZone(zone).toOffsetDateTime(),
                                    end.atZone(zone).toOffsetDateTime()));
                        }
                    }
                }
                slots.sort(Comparator.comparing(s -> s.start().toInstant()));
            }
            days.add(new Day(date, List.copyOf(slots)));
        }
        return List.copyOf(days);
    }

    /** Slots that start at or after {@code now}; a slot starting exactly at {@code now} is kept. */
    public static List<Slot> notStartedBefore(List<Slot> slots, Instant now) {
        return slots.stream().filter(s -> !s.start().toInstant().isBefore(now)).toList();
    }

    private static boolean anyFree(Collection<? extends Collection<Interval>> resources, Instant start, Instant end) {
        for (Collection<Interval> busy : resources) {
            if (busy.stream().noneMatch(i -> i.overlaps(start, end))) {
                return true;
            }
        }
        return false;
    }
}
