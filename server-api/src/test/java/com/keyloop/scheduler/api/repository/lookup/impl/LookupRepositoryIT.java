package com.keyloop.scheduler.api.repository.lookup.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.keyloop.scheduler.api.model.lookup.NamedResource;
import com.keyloop.scheduler.api.repository.lookup.LookupRepository;
import com.keyloop.scheduler.api.support.Fixtures;
import com.keyloop.scheduler.api.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the lookup SQL on real Postgres (Flyway schema): null filters, vehicle fit,
 * skill, inactive resources, other dealership, half-open overlap and cancelled bookings.
 * Fixed far-future day. Each test rolls back.
 */
@Transactional
class LookupRepositoryIT extends IntegrationTest {

    private static final String DAY = "2030-01-07";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private LookupRepository repository;

    private UUID dealer;
    private UUID otherDealer;
    private UUID service;
    private UUID otherService;
    private UUID suv;
    private UUID ev;
    private UUID bookedBay;
    private UUID suvBay;
    private UUID evBay;
    private UUID inactiveBay;
    private UUID bookedTech;
    private UUID suvTech;
    private UUID evTech;

    @BeforeEach
    void seed() {
        Fixtures fixtures = new Fixtures(jdbc);
        dealer = fixtures.dealership("UTC", "00:00", "24:00");
        otherDealer = fixtures.dealership("UTC", "00:00", "24:00");
        service = fixtures.serviceType(60);
        otherService = fixtures.serviceType(30);
        suv = fixtures.vehicleType();
        ev = fixtures.vehicleType();

        bookedBay = fixtures.bay(dealer, suv);
        suvBay = fixtures.bay(dealer, suv);
        evBay = fixtures.bay(dealer, ev);
        inactiveBay = fixtures.bay(UUID.randomUUID(), dealer, false, suv);
        fixtures.bay(otherDealer, suv);

        bookedTech = fixtures.technician(dealer, service, suv);
        suvTech = fixtures.technician(dealer, service, suv);
        evTech = fixtures.technician(dealer, otherService, ev);
        fixtures.technician(otherDealer, service, suv);

        fixtures.appointment(dealer, service, suv, bookedBay, bookedTech, at("09:00"), at("10:00"), "CONFIRMED");
        fixtures.appointment(dealer, service, suv, suvBay, suvTech, at("12:00"), at("13:00"), "CANCELLED");
    }

    @Test
    void nullFiltersListEveryActiveFreeResourceOfTheDealership() {
        assertThat(ids(repository.findFreeBays(dealer, null, null, at("09:30").toInstant(), at("10:30").toInstant())))
                .containsExactlyInAnyOrder(suvBay, evBay);
        assertThat(ids(repository.findFreeTechnicians(dealer, null, null, null,
                at("09:30").toInstant(), at("10:30").toInstant())))
                .containsExactlyInAnyOrder(suvTech, evTech);
    }

    @Test
    void vehicleTypeAndSkillNarrowTheSearch() {
        Instant from = at("11:00").toInstant();
        Instant to = at("12:00").toInstant();
        assertThat(ids(repository.findFreeBays(dealer, null, ev, from, to))).containsExactly(evBay);
        assertThat(ids(repository.findFreeTechnicians(dealer, null, service, suv, from, to)))
                .containsExactlyInAnyOrder(bookedTech, suvTech);
        assertThat(ids(repository.findFreeTechnicians(dealer, null, otherService, suv, from, to))).isEmpty();
    }

    @Test
    void exactIdsAnswerOneSlotWithHalfOpenOverlapAndCancelledIgnored() {
        // Booked 09:00-10:00
        assertThat(repository.findFreeBays(dealer, bookedBay, null, at("09:59").toInstant(), at("11:00").toInstant()))
                .isEmpty();
        assertThat(ids(repository.findFreeBays(dealer, bookedBay, null, at("10:00").toInstant(), at("11:00").toInstant())))
                .containsExactly(bookedBay);
        assertThat(ids(repository.findFreeTechnicians(dealer, bookedTech, null, null,
                at("08:00").toInstant(), at("09:00").toInstant()))).containsExactly(bookedTech);
        // CANCELLED 12:00-13:00 does not block
        assertThat(ids(repository.findFreeBays(dealer, suvBay, suv, at("12:00").toInstant(), at("13:00").toInstant())))
                .containsExactly(suvBay);
        // Inactive bay is never free
        assertThat(repository.findFreeBays(dealer, inactiveBay, null, at("12:00").toInstant(), at("13:00").toInstant()))
                .isEmpty();
    }

    @Test
    void belongsToChecksTheDealership() {
        assertThat(repository.bayBelongsTo(suvBay, dealer)).isTrue();
        assertThat(repository.bayBelongsTo(suvBay, otherDealer)).isFalse();
        assertThat(repository.bayBelongsTo(UUID.randomUUID(), dealer)).isFalse();
        assertThat(repository.technicianBelongsTo(suvTech, dealer)).isTrue();
        assertThat(repository.technicianBelongsTo(suvTech, otherDealer)).isFalse();
    }

    private static OffsetDateTime at(String time) {
        return OffsetDateTime.parse(DAY + "T" + time + ":00Z");
    }

    private static List<UUID> ids(List<NamedResource> resources) {
        return resources.stream().map(NamedResource::id).toList();
    }
}
