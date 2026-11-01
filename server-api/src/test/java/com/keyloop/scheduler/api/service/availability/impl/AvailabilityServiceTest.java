package com.keyloop.scheduler.api.service.availability.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.keyloop.scheduler.api.config.availability.AvailabilityProperties;
import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse;
import com.keyloop.scheduler.api.exception.availability.InvalidRangeException;
import com.keyloop.scheduler.api.model.availability.Booking;
import com.keyloop.scheduler.api.model.availability.Dealership;
import com.keyloop.scheduler.api.repository.availability.AvailabilityRepository;
import com.keyloop.scheduler.api.support.PassThroughAvailabilityCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AvailabilityServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 2);

    // find(): one UTC dealership open 08:00-17:00, 60-min service, far-future day
    private static final LocalDate DAY = LocalDate.of(2030, 1, 7);
    private static final UUID DEALER = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();
    private static final UUID BAY_A = UUID.randomUUID();
    private static final UUID BAY_B = UUID.randomUUID();
    private static final UUID TECH_T = UUID.randomUUID();
    private static final UUID TECH_U = UUID.randomUUID();
    /** Holds a booking but does not fit this query (e.g. not certified for the vehicle type). */
    private static final UUID OTHER_TECH = UUID.randomUUID();
    private static final Clock LONG_AGO = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final AvailabilityProperties STEP_15 = new AvailabilityProperties(Duration.ofMinutes(15));

    private final AvailabilityRepository repository = mock(AvailabilityRepository.class);

    @BeforeEach
    void knownIds() {
        when(repository.findDealership(DEALER)).thenReturn(Optional.of(new Dealership("UTC", 8 * 60, 17 * 60)));
        when(repository.findServiceDurationMinutes(SERVICE)).thenReturn(Optional.of(60));
        when(repository.vehicleTypeExists(VEHICLE)).thenReturn(true);
    }

    private void resources(List<UUID> bays, List<UUID> technicians, Booking... bookings) {
        when(repository.findFittingBayIds(DEALER, VEHICLE)).thenReturn(bays);
        when(repository.findQualifiedTechnicianIds(DEALER, SERVICE, VEHICLE)).thenReturn(technicians);
        when(repository.findBookings(any(), any(), any(), any(), any())).thenReturn(List.of(bookings));
    }

    private static Booking booking(UUID bay, UUID tech, String from, String to) {
        return new Booking(bay, tech, Instant.parse(DAY + "T" + from + ":00Z"), Instant.parse(DAY + "T" + to + ":00Z"));
    }

    private List<String> starts(Clock clock) {
        AvailabilityResponse response = new AvailabilityServiceImpl(repository, new PassThroughAvailabilityCache(), clock, STEP_15).find(DEALER, SERVICE, VEHICLE, DAY, DAY);
        return response.days().getFirst().slots().stream().map(s -> s.start().toLocalTime().toString()).toList();
    }

    @Test
    void bookingBlocksOnlyItsOwnBayAndTechnician() {
        resources(List.of(BAY_A, BAY_B), List.of(TECH_T, TECH_U), booking(BAY_A, TECH_T, "09:00", "10:00"));

        // Bay B + tech U are still free
        assertThat(starts(LONG_AGO)).contains("09:00", "09:15");
    }

    @Test
    void bookedTechnicianBlocksWhenItIsTheOnlyOne() {
        resources(List.of(BAY_A, BAY_B), List.of(TECH_T), booking(BAY_A, TECH_T, "09:00", "10:00"));

        assertThat(starts(LONG_AGO)).contains("08:00", "10:00").doesNotContain("08:15", "09:00", "09:45");
    }

    @Test
    void bookingWithANonFittingTechnicianStillBlocksTheFittingBay() {
        resources(List.of(BAY_A), List.of(TECH_T, TECH_U), booking(BAY_A, OTHER_TECH, "09:00", "10:00"));

        assertThat(starts(LONG_AGO)).contains("08:00", "10:00").doesNotContain("08:15", "09:00", "09:45");
    }

    @Test
    void noFittingBaySkipsTheBookingQuery() {
        resources(List.of(), List.of(TECH_T));

        assertThat(starts(LONG_AGO)).isEmpty();
        verify(repository, never()).findBookings(any(), any(), any(), any(), any());
    }

    @Test
    void noQualifiedTechnicianSkipsTheBookingQuery() {
        resources(List.of(BAY_A), List.of());

        assertThat(starts(LONG_AGO)).isEmpty();
        verify(repository, never()).findBookings(any(), any(), any(), any(), any());
    }

    @Test
    void slotsBeforeTheClockAreDropped() {
        resources(List.of(BAY_A), List.of(TECH_T));
        Clock now = Clock.fixed(Instant.parse(DAY + "T10:05:00Z"), ZoneOffset.UTC);

        assertThat(starts(now).getFirst()).isEqualTo("10:15");
    }

    @Test
    void bookingsAreLoadedForTheLocalSpanOfTheRange() {
        resources(List.of(BAY_A), List.of(TECH_T));

        starts(LONG_AGO);

        verify(repository).findBookings(DEALER, List.of(BAY_A), List.of(TECH_T),
                Instant.parse(DAY + "T00:00:00Z"), Instant.parse(DAY.plusDays(1) + "T00:00:00Z"));
    }

    @Test
    void fourteenDaysInclusiveIsAllowed() {
        assertThatCode(() -> AvailabilityServiceImpl.validateRange(FROM, FROM.plusDays(13))).doesNotThrowAnyException();
        assertThatCode(() -> AvailabilityServiceImpl.validateRange(FROM, FROM)).doesNotThrowAnyException();
    }

    @Test
    void fifteenDaysIsRejected() {
        assertThatThrownBy(() -> AvailabilityServiceImpl.validateRange(FROM, FROM.plusDays(14)))
                .isInstanceOf(InvalidRangeException.class);
    }

    @Test
    void toBeforeFromIsRejected() {
        assertThatThrownBy(() -> AvailabilityServiceImpl.validateRange(FROM, FROM.minusDays(1)))
                .isInstanceOf(InvalidRangeException.class);
    }

    @Test
    void extremeDatesAreRejectedAsBadRange() {
        assertThatThrownBy(() -> new AvailabilityServiceImpl(repository, new PassThroughAvailabilityCache(), LONG_AGO, STEP_15)
                .find(DEALER, SERVICE, VEHICLE, LocalDate.MAX, LocalDate.MAX))
                .isInstanceOf(InvalidRangeException.class);
        assertThatThrownBy(() -> AvailabilityServiceImpl.validateRange(LocalDate.MIN, LocalDate.MIN))
                .isInstanceOf(InvalidRangeException.class);
        assertThatCode(() -> AvailabilityServiceImpl.validateRange(LocalDate.of(9999, 12, 31), LocalDate.of(9999, 12, 31)))
                .doesNotThrowAnyException();
    }
}
