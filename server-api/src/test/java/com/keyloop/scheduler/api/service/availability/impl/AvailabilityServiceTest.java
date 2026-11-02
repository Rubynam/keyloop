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
import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.exception.availability.InvalidRangeException;
import com.keyloop.scheduler.api.model.availability.Booking;
import com.keyloop.scheduler.api.model.availability.Dealership;
import com.keyloop.scheduler.api.model.availability.ServiceBay;
import com.keyloop.scheduler.api.model.availability.ServiceType;
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
        when(repository.findDealership(DEALER)).thenReturn(Optional.of(new Dealership("Hub", "UTC", 8 * 60, 17 * 60)));
        when(repository.findServiceType(SERVICE)).thenReturn(Optional.of(new ServiceType("Oil change", 60)));
        when(repository.findVehicleTypeName(VEHICLE)).thenReturn(Optional.of("SUV"));
        when(repository.findServiceBay(DEALER, BAY_A, VEHICLE)).thenReturn(Optional.of(new ServiceBay("Bay A", true, true)));
    }

    private void resources(List<UUID> technicians, Booking... bookings) {
        when(repository.findQualifiedTechnicianIds(DEALER, SERVICE, VEHICLE)).thenReturn(technicians);
        when(repository.findBookings(any(), any(), any(), any(), any())).thenReturn(List.of(bookings));
    }

    private static Booking booking(UUID bay, UUID tech, String from, String to) {
        return new Booking(bay, tech, Instant.parse(DAY + "T" + from + ":00Z"), Instant.parse(DAY + "T" + to + ":00Z"));
    }

    private AvailabilityResponse find(Clock clock) {
        return new AvailabilityServiceImpl(repository, new PassThroughAvailabilityCache(), clock, STEP_15)
                .find(DEALER, BAY_A, SERVICE, VEHICLE, DAY, DAY);
    }

    private List<String> starts(Clock clock) {
        AvailabilityResponse response = find(clock);
        return response.days().getFirst().slots().stream().map(s -> s.start().toLocalTime().toString()).toList();
    }

    @Test
    void bookingOnTheAskedBayBlocksItEvenWithAFreeTechnician() {
        resources(List.of(TECH_T, TECH_U), booking(BAY_A, TECH_T, "09:00", "10:00"));

        // Tech U is free, but bay A is not
        assertThat(starts(LONG_AGO)).contains("08:00", "10:00").doesNotContain("08:15", "09:00", "09:45");
    }

    @Test
    void bookingOnAnotherBayLeavesTheAskedBayFree() {
        resources(List.of(TECH_T, TECH_U), booking(BAY_B, TECH_T, "09:00", "10:00"));

        // Bay A + tech U are still free
        assertThat(starts(LONG_AGO)).contains("09:00", "09:15");
    }

    @Test
    void onlyTechnicianBusyOnAnotherBayBlocksTheAskedBay() {
        resources(List.of(TECH_T), booking(BAY_B, TECH_T, "09:00", "10:00"));

        assertThat(starts(LONG_AGO)).contains("08:00", "10:00").doesNotContain("08:15", "09:00", "09:45");
    }

    @Test
    void bookingWithANonFittingTechnicianStillBlocksTheAskedBay() {
        resources(List.of(TECH_T, TECH_U), booking(BAY_A, OTHER_TECH, "09:00", "10:00"));

        assertThat(starts(LONG_AGO)).contains("08:00", "10:00").doesNotContain("08:15", "09:00", "09:45");
    }

    @Test
    void bayThatDoesNotTakeTheVehicleTypeGivesNoSlotsAndSkipsTheBookingQuery() {
        when(repository.findServiceBay(DEALER, BAY_A, VEHICLE)).thenReturn(Optional.of(new ServiceBay("Car bay", true, false)));
        resources(List.of(TECH_T));

        assertThat(starts(LONG_AGO)).isEmpty();
        verify(repository, never()).findBookings(any(), any(), any(), any(), any());
    }

    @Test
    void inactiveBayGivesNoSlots() {
        when(repository.findServiceBay(DEALER, BAY_A, VEHICLE)).thenReturn(Optional.of(new ServiceBay("Old bay", false, true)));
        resources(List.of(TECH_T));

        assertThat(starts(LONG_AGO)).isEmpty();
    }

    @Test
    void unknownOrForeignBayIsNotFound() {
        when(repository.findServiceBay(DEALER, BAY_A, VEHICLE)).thenReturn(Optional.empty());
        resources(List.of(TECH_T));

        assertThatThrownBy(() -> find(LONG_AGO))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Service bay").hasMessageContaining(BAY_A.toString());
    }

    @Test
    void responseNamesTheAskedBay() {
        resources(List.of(TECH_T));

        AvailabilityResponse response = find(LONG_AGO);

        assertThat(response.serviceBayId()).isEqualTo(BAY_A);
        assertThat(response.serviceBayName()).isEqualTo("Bay A");
    }

    @Test
    void noQualifiedTechnicianSkipsTheBookingQuery() {
        resources(List.of());

        assertThat(starts(LONG_AGO)).isEmpty();
        verify(repository, never()).findBookings(any(), any(), any(), any(), any());
    }

    @Test
    void slotsBeforeTheClockAreDropped() {
        resources(List.of(TECH_T));
        Clock now = Clock.fixed(Instant.parse(DAY + "T10:05:00Z"), ZoneOffset.UTC);

        assertThat(starts(now).getFirst()).isEqualTo("10:15");
    }

    @Test
    void bookingsAreLoadedForTheLocalSpanOfTheRange() {
        resources(List.of(TECH_T));

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
                .find(DEALER, BAY_A, SERVICE, VEHICLE, LocalDate.MAX, LocalDate.MAX))
                .isInstanceOf(InvalidRangeException.class);
        assertThatThrownBy(() -> AvailabilityServiceImpl.validateRange(LocalDate.MIN, LocalDate.MIN))
                .isInstanceOf(InvalidRangeException.class);
        assertThatCode(() -> AvailabilityServiceImpl.validateRange(LocalDate.of(9999, 12, 31), LocalDate.of(9999, 12, 31)))
                .doesNotThrowAnyException();
    }
}
