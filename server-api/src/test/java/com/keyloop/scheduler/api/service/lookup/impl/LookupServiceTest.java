package com.keyloop.scheduler.api.service.lookup.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.keyloop.scheduler.api.dto.lookup.AvailabilityLookupQuery;
import com.keyloop.scheduler.api.dto.lookup.AvailabilityLookupResponse;
import com.keyloop.scheduler.api.dto.lookup.AvailabilityLookupResponse.Pair;
import com.keyloop.scheduler.api.dto.lookup.WindowProblem;
import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.exception.lookup.InvalidWindowException;
import com.keyloop.scheduler.api.model.availability.Dealership;
import com.keyloop.scheduler.api.model.availability.ServiceType;
import com.keyloop.scheduler.api.model.lookup.NamedResource;
import com.keyloop.scheduler.api.repository.availability.AvailabilityRepository;
import com.keyloop.scheduler.api.repository.lookup.LookupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One dealership in Ho Chi Minh City (UTC+7), open 08:00-17:00 local, far-future day. */
class LookupServiceTest {

    private static final String DAY = "2030-01-07";
    private static final UUID DEALER = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();
    private static final NamedResource BAY_1 = new NamedResource(UUID.randomUUID(), "Bay 1");
    private static final NamedResource BAY_2 = new NamedResource(UUID.randomUUID(), "Bay 2");
    private static final NamedResource AN = new NamedResource(UUID.randomUUID(), "An");
    private static final Clock LONG_AGO = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final AvailabilityRepository catalogue = mock(AvailabilityRepository.class);
    private final LookupRepository lookups = mock(LookupRepository.class);

    @BeforeEach
    void knownIds() {
        when(catalogue.findDealership(DEALER))
                .thenReturn(Optional.of(new Dealership("Hub", "Asia/Ho_Chi_Minh", 8 * 60, 17 * 60)));
        when(catalogue.findServiceType(SERVICE)).thenReturn(Optional.of(new ServiceType("Oil change", 60)));
        when(catalogue.findVehicleTypeName(VEHICLE)).thenReturn(Optional.of("SUV"));
        when(lookups.bayBelongsTo(any(), any())).thenReturn(true);
        when(lookups.technicianBelongsTo(any(), any())).thenReturn(true);
        when(lookups.findFreeBays(any(), any(), any(), any(), any())).thenReturn(List.of(BAY_1, BAY_2));
        when(lookups.findFreeTechnicians(any(), any(), any(), any(), any(), any())).thenReturn(List.of(AN));
    }

    private static OffsetDateTime local(String time) {
        return OffsetDateTime.parse(DAY + "T" + time + ":00+07:00");
    }

    private AvailabilityLookupResponse lookup(Clock clock, AvailabilityLookupQuery query) {
        return new LookupServiceImpl(catalogue, lookups, clock).lookup(query);
    }

    private AvailabilityLookupResponse lookup(String from, String to, UUID service) {
        return lookup(LONG_AGO, new AvailabilityLookupQuery(local(from), local(to), DEALER, null, null, service, null));
    }

    @Test
    void pairsEveryFreeBayWithEveryFreeTechnician() {
        AvailabilityLookupResponse response = lookup("09:00", "10:00", SERVICE);

        assertThat(response.available()).isTrue();
        assertThat(response.reasons()).isEmpty();
        assertThat(response.totalPairs()).isEqualTo(2);
        assertThat(response.pairs()).containsExactly(
                new Pair(BAY_1.id(), "Bay 1", AN.id(), "An"),
                new Pair(BAY_2.id(), "Bay 2", AN.id(), "An"));
        assertThat(response.dealershipName()).isEqualTo("Hub");
        assertThat(response.serviceTypeName()).isEqualTo("Oil change");
        assertThat(response.vehicleTypeId()).isNull();
        // Times come back with the dealership's offset
        assertThat(response.from()).isEqualTo(local("09:00"));
        assertThat(response.from().getOffset()).isEqualTo(ZoneOffset.ofHours(7));
    }

    @Test
    void passesEveryFilterToTheRepository() {
        UUID bay = BAY_1.id();
        UUID tech = AN.id();
        AvailabilityLookupQuery exact = new AvailabilityLookupQuery(local("09:00"), local("10:00"), DEALER,
                bay, tech, SERVICE, VEHICLE);
        Instant from = local("09:00").toInstant();
        Instant to = local("10:00").toInstant();
        when(lookups.findFreeBays(DEALER, bay, VEHICLE, from, to)).thenReturn(List.of(BAY_1));
        when(lookups.findFreeTechnicians(DEALER, tech, SERVICE, VEHICLE, from, to)).thenReturn(List.of(AN));

        AvailabilityLookupResponse response = lookup(LONG_AGO, exact);

        assertThat(response.pairs()).containsExactly(new Pair(bay, "Bay 1", tech, "An"));
        assertThat(response.vehicleTypeName()).isEqualTo("SUV");
    }

    @Test
    void noFreeTechnicianMeansNotAvailable() {
        when(lookups.findFreeTechnicians(any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        AvailabilityLookupResponse response = lookup("09:00", "10:00", null);

        assertThat(response.available()).isFalse();
        assertThat(response.totalPairs()).isZero();
        assertThat(response.pairs()).isEmpty();
        assertThat(response.reasons()).isEmpty();
    }

    @Test
    void noFreeBaySkipsTheTechnicianQuery() {
        when(lookups.findFreeBays(any(), any(), any(), any(), any())).thenReturn(List.of());

        assertThat(lookup("09:00", "10:00", null).available()).isFalse();
        verify(lookups, never()).findFreeTechnicians(any(), any(), any(), any(), any(), any());
    }

    @Test
    void windowProblemsGiveNoPairsAndNoResourceQuery() {
        assertThat(lookup("07:30", "08:30", null).reasons()).containsExactly(WindowProblem.OUTSIDE_BUSINESS_HOURS);
        assertThat(lookup("16:30", "17:30", null).reasons()).containsExactly(WindowProblem.OUTSIDE_BUSINESS_HOURS);
        assertThat(lookup("09:00", "09:30", SERVICE).reasons()).containsExactly(WindowProblem.SHORTER_THAN_SERVICE);
        Clock afterStart = Clock.fixed(local("09:10").toInstant(), ZoneOffset.UTC);
        AvailabilityLookupResponse past = lookup(afterStart,
                new AvailabilityLookupQuery(local("09:00"), local("10:00"), DEALER, null, null, null, null));
        assertThat(past.reasons()).containsExactly(WindowProblem.STARTS_IN_PAST);
        assertThat(past.available()).isFalse();
        assertThat(past.pairs()).isEmpty();
        verify(lookups, never()).findFreeBays(any(), any(), any(), any(), any());

        // Exactly open to close is fine
        assertThat(lookup("08:00", "17:00", null).reasons()).isEmpty();
    }

    @Test
    void pairsAreCappedButCounted() {
        List<NamedResource> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(new NamedResource(UUID.randomUUID(), "R" + i));
        }
        when(lookups.findFreeBays(any(), any(), any(), any(), any())).thenReturn(many);
        when(lookups.findFreeTechnicians(any(), any(), any(), any(), any(), any())).thenReturn(many);

        AvailabilityLookupResponse response = lookup("09:00", "10:00", null);

        assertThat(response.totalPairs()).isEqualTo(900);
        assertThat(response.pairs()).hasSize(200);
    }

    @Test
    void badWindowIs400BeforeAnyRead() {
        assertThatThrownBy(() -> lookup("10:00", "10:00", null)).isInstanceOf(InvalidWindowException.class)
                .hasMessageContaining("must be after");
        assertThatThrownBy(() -> lookup(LONG_AGO, new AvailabilityLookupQuery(local("09:00"),
                local("09:00").plusMinutes(1441), DEALER, null, null, null, null)))
                .isInstanceOf(InvalidWindowException.class)
                .hasMessage("Window is 1441 minutes; at most 1440 are allowed");
        verify(catalogue, never()).findDealership(any());
    }

    @Test
    void unknownIdsAndResourcesOfAnotherDealershipAre404() {
        UUID stranger = UUID.randomUUID();
        when(lookups.technicianBelongsTo(stranger, DEALER)).thenReturn(false);
        assertThatThrownBy(() -> lookup(LONG_AGO, new AvailabilityLookupQuery(local("09:00"), local("10:00"),
                DEALER, null, stranger, null, null)))
                .isInstanceOf(NotFoundException.class).hasMessage("Technician " + stranger + " not found");

        when(lookups.bayBelongsTo(stranger, DEALER)).thenReturn(false);
        assertThatThrownBy(() -> lookup(LONG_AGO, new AvailabilityLookupQuery(local("09:00"), local("10:00"),
                DEALER, stranger, null, null, null)))
                .isInstanceOf(NotFoundException.class).hasMessage("Service bay " + stranger + " not found");

        UUID unknownDealer = UUID.randomUUID();
        when(catalogue.findDealership(unknownDealer)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> lookup(LONG_AGO, new AvailabilityLookupQuery(local("09:00"), local("10:00"),
                unknownDealer, null, null, null, null)))
                .isInstanceOf(NotFoundException.class).hasMessage("Dealership " + unknownDealer + " not found");
    }
}
