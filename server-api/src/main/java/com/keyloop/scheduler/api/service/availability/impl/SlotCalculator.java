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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse.Day;
import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse.Slot;
import com.keyloop.scheduler.api.model.availability.Interval;
import com.keyloop.scheduler.api.model.availability.Technician;

/**
 * Pure free-slot computation (DLD §8, spec-read-availability). No I/O: everything it
 * needs is passed in, so every edge case is unit-testable without a database.
 *
 * <ul>
 *   <li>Candidate starts every {@code stepMinutes} (config {@code slot-step}) of local wall-clock time from
 *       {@code open}; a local time skipped by a DST gap is not a candidate, a local time
 *       repeated by a DST overlap is a candidate once per offset.</li>
 *   <li>Window {@code [start, start + duration)} must end at or before local {@code close}
 *       ({@code 1440} = end of day).</li>
 *   <li>The now-cut is not part of {@link #compute}: the full day is what the cache
 *       stores, and {@link #notStartedBefore} drops past starts per request.</li>
 *   <li>A window is free when at least one bay AND at least one technician have no busy
 *       interval overlapping it ({@code a.start < b.end && b.start < a.end}). Bays and
 *       technicians are independent: the DB fit checks are per resource, not per pair.</li>
 *   <li>The slot names one of its free technicians, with the booking rule
 *       ({@code CandidateSelectorImpl}): fewest jobs starting that local day, then id. This
 *       only names the technician; it never changes which windows are free.</li>
 * </ul>
 *
 * <pre>
 * 10:00-11:00   T1 free, 3 jobs today
 *               T2 free, 1 job today   → slot 10:00-11:00, technician T2
 *               T3 busy
 * </pre>
 */
public final class SlotCalculator {

    /** Same order as PostgreSQL {@code ORDER BY id} and {@code CandidateSelectorImpl}: the hex text order. */
    private static final Comparator<Technician> BY_ID = Comparator.comparing(t -> t.id().toString());

    private SlotCalculator() {}

    /**
     * One qualified technician and every busy interval it has in the loaded range, on
     * any bay. The intervals also give its job count per local day.
     *
     * <pre>
     * TechnicianSchedule(Technician(T1, "An"), [09:00-10:00, 13:00-14:00])   → 2 jobs that day
     * </pre>
     */
    public record TechnicianSchedule(Technician technician, Collection<Interval> busy) {}

    /**
     * @param stepMinutes distance between two candidate starts ({@code slot-step}, Spec07 D4)
     * @param bays        one entry per fitting bay: its busy intervals (empty = free all day)
     * @param technicians one entry per qualified technician, with its busy intervals
     * @return one {@link Day} per local date in {@code [from, to]}, slots ordered by start
     */
    public static List<Day> compute(LocalDate from, LocalDate to, ZoneId zone,
                                    int openMinute, int closeMinute, int durationMinutes, int stepMinutes,
                                    Collection<? extends Collection<Interval>> bays,
                                    Collection<TechnicianSchedule> technicians) {
        ZoneRules rules = zone.getRules();
        Duration duration = Duration.ofMinutes(durationMinutes);
        List<Day> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<Slot> slots = new ArrayList<>();
            if (!bays.isEmpty() && !technicians.isEmpty()) {
                LocalDateTime midnight = date.atStartOfDay();
                Map<UUID, Long> jobs = jobsOn(date, zone, technicians);
                Instant close = ZonedDateTime.ofLocal(midnight.plusMinutes(closeMinute), zone, null)
                        .withLaterOffsetAtOverlap().toInstant();
                for (int m = openMinute; m < closeMinute; m += stepMinutes) {
                    LocalDateTime local = midnight.plusMinutes(m);
                    // Empty in a DST gap, two offsets in a DST overlap
                    for (ZoneOffset offset : rules.getValidOffsets(local)) {
                        Instant start = local.toInstant(offset);
                        Instant end = start.plus(duration);
                        if (end.isAfter(close)) {
                            continue;
                        }
                        if (!anyFree(bays, start, end)) {
                            continue;
                        }
                        Optional<Technician> technician = pickFree(technicians, jobs, start, end);
                        technician.ifPresent(t -> slots.add(new Slot(start.atZone(zone).toOffsetDateTime(),
                                end.atZone(zone).toOffsetDateTime(), t.id(), t.name())));
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

    /** Jobs of each technician that start on the local {@code date}, as the booking rule counts them. */
    private static Map<UUID, Long> jobsOn(LocalDate date, ZoneId zone, Collection<TechnicianSchedule> technicians) {
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        Map<UUID, Long> jobs = new HashMap<>();
        for (TechnicianSchedule schedule : technicians) {
            jobs.put(schedule.technician().id(), schedule.busy().stream()
                    .filter(i -> !i.start().isBefore(dayStart) && i.start().isBefore(dayEnd))
                    .count());
        }
        return jobs;
    }

    /** Free technician with the fewest jobs that day, then the lowest id; empty when none is free. */
    private static Optional<Technician> pickFree(Collection<TechnicianSchedule> technicians, Map<UUID, Long> jobs,
                                                 Instant start, Instant end) {
        return technicians.stream()
                .filter(t -> t.busy().stream().noneMatch(i -> i.overlaps(start, end)))
                .map(TechnicianSchedule::technician)
                .min(Comparator.<Technician>comparingLong(t -> jobs.get(t.id())).thenComparing(BY_ID));
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
