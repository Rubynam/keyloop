package com.keyloop.scheduler.api.availability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import com.keyloop.scheduler.api.availability.AvailabilityResponse.Day;
import com.keyloop.scheduler.api.availability.AvailabilityResponse.Slot;
import com.keyloop.scheduler.api.availability.SlotCalculator.Interval;
import org.junit.jupiter.api.Test;

class SlotCalculatorTest {

    private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");
    private static final List<Interval> FREE = List.of();

    private static Instant at(LocalDate date, String time, ZoneId zone) {
        return date.atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant();
    }

    private static List<String> localStarts(Day day) {
        return day.slots().stream().map(s -> s.start().toLocalTime().toString()).toList();
    }

    private static int minutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    @Test
    void happyPathOffersEveryQuarterHourThatFitsBeforeClose() {
        List<Day> days = SlotCalculator.compute(DAY, DAY, HCM, minutes("08:00"), minutes("17:00"), 60,
                List.of(FREE), List.of(FREE), LONG_AGO);

        assertThat(days).hasSize(1);
        Day day = days.getFirst();
        assertThat(day.date()).isEqualTo(DAY);
        // 08:00 .. 16:00 every 15 min = 33 starts
        assertThat(day.slots()).hasSize(33);
        Slot first = day.slots().getFirst();
        assertThat(first.start()).isEqualTo(OffsetDateTime.parse("2026-11-02T08:00+07:00"));
        assertThat(first.end()).isEqualTo(OffsetDateTime.parse("2026-11-02T09:00+07:00"));
        assertThat(day.slots().getLast().start().toLocalTime().toString()).isEqualTo("16:00");
    }

    @Test
    void backToBackJobLeavesItsEndFreeButNotItsInside() {
        List<Interval> busy = List.of(new Interval(at(DAY, "09:00", HCM), at(DAY, "10:00", HCM)));

        Day day = SlotCalculator.compute(DAY, DAY, HCM, minutes("08:00"), minutes("17:00"), 60,
                List.of(busy), List.of(busy), LONG_AGO).getFirst();

        // 08:00 ends exactly at 09:00 (half-open) -> free; 08:15 .. 09:45 overlap
        assertThat(localStarts(day)).contains("08:00", "10:00")
                .doesNotContain("08:15", "09:00", "09:15", "09:45");
    }

    @Test
    void busyTechnicianBlocksEvenWithAFreeBay() {
        List<Interval> busy = List.of(new Interval(at(DAY, "09:00", HCM), at(DAY, "10:00", HCM)));

        Day day = SlotCalculator.compute(DAY, DAY, HCM, minutes("08:00"), minutes("17:00"), 60,
                List.of(FREE), List.of(busy), LONG_AGO).getFirst();

        assertThat(localStarts(day)).doesNotContain("09:00", "09:15");
    }

    @Test
    void anyFreeBayPlusAnyFreeTechnicianIsEnough() {
        List<Interval> busy = List.of(new Interval(at(DAY, "09:00", HCM), at(DAY, "10:00", HCM)));

        Day day = SlotCalculator.compute(DAY, DAY, HCM, minutes("08:00"), minutes("17:00"), 60,
                List.of(busy, FREE), List.of(FREE, busy), LONG_AGO).getFirst();

        assertThat(localStarts(day)).contains("09:00", "09:15");
    }

    @Test
    void cancelledJobsAreNotBusy() {
        // The repository filters CANCELLED out, so the calculator just sees no busy interval
        Day day = SlotCalculator.compute(DAY, DAY, HCM, minutes("08:00"), minutes("17:00"), 60,
                List.of(FREE), List.of(FREE), LONG_AGO).getFirst();

        assertThat(localStarts(day)).contains("09:00", "09:15");
    }

    @Test
    void serviceLongerThanOpeningHoursHasNoSlots() {
        Day day = SlotCalculator.compute(DAY, DAY, HCM, minutes("08:00"), minutes("17:00"), 600,
                List.of(FREE), List.of(FREE), LONG_AGO).getFirst();

        assertThat(day.slots()).isEmpty();
    }

    @Test
    void openAllDayEndsWithTheSlotUpToMidnight() {
        Day day = SlotCalculator.compute(DAY, DAY, UTC, 0, 1440, 60,
                List.of(FREE), List.of(FREE), LONG_AGO).getFirst();

        assertThat(day.slots()).hasSize(93); // 00:00 .. 23:00
        Slot last = day.slots().getLast();
        assertThat(last.start()).isEqualTo(OffsetDateTime.parse("2026-11-02T23:00Z"));
        assertThat(last.end()).isEqualTo(OffsetDateTime.parse("2026-11-03T00:00Z"));
    }

    @Test
    void fullDayServiceFitsOnlyAt24hDealer() {
        Day day = SlotCalculator.compute(DAY, DAY, UTC, 0, 1440, 1440,
                List.of(FREE), List.of(FREE), LONG_AGO).getFirst();

        assertThat(localStarts(day)).containsExactly("00:00");
    }

    @Test
    void dstEndFollowsWallClockAndChangesOffset() {
        LocalDate before = LocalDate.of(2026, 10, 31);
        LocalDate dstEnd = LocalDate.of(2026, 11, 1);

        List<Day> days = SlotCalculator.compute(before, dstEnd, NEW_YORK, minutes("07:00"), minutes("19:00"), 60,
                List.of(FREE), List.of(FREE), LONG_AGO);

        Day sat = days.get(0);
        Day sun = days.get(1);
        assertThat(sat.slots().getFirst().start()).isEqualTo(OffsetDateTime.parse("2026-10-31T07:00-04:00"));
        assertThat(sun.slots().getFirst().start()).isEqualTo(OffsetDateTime.parse("2026-11-01T07:00-05:00"));
        assertThat(sun.slots().getLast().start()).isEqualTo(OffsetDateTime.parse("2026-11-01T18:00-05:00"));
        assertThat(sat.slots()).allMatch(s -> s.start().getOffset().equals(ZoneOffset.ofHours(-4)));
        assertThat(sun.slots()).allMatch(s -> s.start().getOffset().equals(ZoneOffset.ofHours(-5)));
        assertThat(sun.slots()).hasSameSizeAs(sat.slots());
    }

    @Test
    void dstOverlapHourAt24hDealerIsOfferedOncePerOffset() {
        LocalDate dstEnd = LocalDate.of(2026, 11, 1);

        Day day = SlotCalculator.compute(dstEnd, dstEnd, NEW_YORK, 0, 1440, 60,
                List.of(FREE), List.of(FREE), LONG_AGO).getFirst();

        // 25-hour day: 01:00-01:45 happen twice
        assertThat(day.slots()).hasSize(97);
        assertThat(day.slots()).extracting(Slot::start).contains(
                OffsetDateTime.parse("2026-11-01T01:15-04:00"), OffsetDateTime.parse("2026-11-01T01:15-05:00"));
        // Ordered by real time
        assertThat(day.slots()).isSortedAccordingTo(
                (a, b) -> a.start().toInstant().compareTo(b.start().toInstant()));
        assertThat(day.slots().getLast().end()).isEqualTo(OffsetDateTime.parse("2026-11-02T00:00-05:00"));
    }

    @Test
    void dstGapLocalTimesAreNotOffered() {
        LocalDate dstStart = LocalDate.of(2026, 3, 8);

        Day day = SlotCalculator.compute(dstStart, dstStart, NEW_YORK, 0, 1440, 15,
                List.of(FREE), List.of(FREE), LONG_AGO).getFirst();

        // 23-hour day: 02:00-02:45 do not exist
        assertThat(day.slots()).hasSize(92);
        assertThat(localStarts(day)).doesNotContain("02:00", "02:30").contains("01:45", "03:00");
    }

    @Test
    void slotsStartingBeforeNowAreDropped() {
        Instant now = at(DAY, "10:05", HCM);

        Day day = SlotCalculator.compute(DAY, DAY, HCM, minutes("08:00"), minutes("17:00"), 60,
                List.of(FREE), List.of(FREE), now).getFirst();

        assertThat(localStarts(day).getFirst()).isEqualTo("10:15");
    }

    @Test
    void slotStartingExactlyNowIsKept() {
        Instant now = at(DAY, "10:00", HCM);

        Day day = SlotCalculator.compute(DAY, DAY, HCM, minutes("08:00"), minutes("17:00"), 60,
                List.of(FREE), List.of(FREE), now).getFirst();

        assertThat(localStarts(day).getFirst()).isEqualTo("10:00");
    }

    @Test
    void noBayOrNoTechnicianGivesEveryDateEmpty() {
        LocalDate to = DAY.plusDays(2);

        List<Day> noBays = SlotCalculator.compute(DAY, to, HCM, minutes("08:00"), minutes("17:00"), 60,
                List.of(), List.of(FREE), LONG_AGO);
        List<Day> noTechs = SlotCalculator.compute(DAY, to, HCM, minutes("08:00"), minutes("17:00"), 60,
                List.of(FREE), List.of(), LONG_AGO);

        assertThat(noBays).hasSize(3).allMatch(d -> d.slots().isEmpty());
        assertThat(noTechs).hasSize(3).allMatch(d -> d.slots().isEmpty());
        assertThat(noBays).extracting(Day::date).containsExactly(DAY, DAY.plusDays(1), to);
    }

    @Test
    void durationNotAMultipleOfTheStepStillStartsOnQuarterHours() {
        Day day = SlotCalculator.compute(DAY, DAY, HCM, minutes("09:00"), minutes("10:00"), 50,
                List.of(FREE), List.of(FREE), LONG_AGO).getFirst();

        assertThat(localStarts(day)).containsExactly("09:00");
        assertThat(day.slots().getFirst().end()).isEqualTo(OffsetDateTime.parse("2026-11-02T09:50+07:00"));
    }
}
