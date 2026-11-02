package com.keyloop.scheduler.api.service.appointment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.model.appointment.CandidateResult;
import com.keyloop.scheduler.api.model.appointment.CandidateResult.Outcome;
import com.keyloop.scheduler.api.model.appointment.CandidateResult.Pair;
import com.keyloop.scheduler.api.service.appointment.CandidateSelector;
import com.keyloop.scheduler.api.support.Fixtures;
import com.keyloop.scheduler.api.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * spec-availability-ops CAP-7 on real PostgreSQL: the client's bay is checked (404 / 422 /
 * busy), then free technicians come in order. Ids are chosen by the test (same prefix,
 * rising last digit) so "T1 < T2 < T3" is also PostgreSQL's {@code id} order. Each test
 * rolls back.
 */
@Transactional
class CandidateSelectorIT extends IntegrationTest {

    /** Window 10:00–11:00 UTC on a far-future day. */
    private static final Instant START = Instant.parse("2030-01-07T10:00:00Z");

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private CandidateSelector selector;

    private Fixtures fixtures;
    private String prefix;
    private UUID dealer;
    private UUID service;
    private UUID vehicleType;
    private UUID otherType;

    @BeforeEach
    void seed() {
        fixtures = new Fixtures(jdbc);
        prefix = String.format("%08x", ThreadLocalRandom.current().nextInt() & 0x7fffffff);
        dealer = fixtures.dealership("UTC", "08:00", "17:00");
        service = fixtures.serviceType(60);
        vehicleType = fixtures.vehicleType();
        otherType = fixtures.vehicleType();
    }

    @Test
    void pairsKeepTheChosenBayAndOnlyChangeTheTechnician() {
        UUID bay = fixtures.bay(id(1, 1), dealer, true, vehicleType);
        // Another free bay is never offered: the client chose
        fixtures.bay(id(1, 2), dealer, true, vehicleType);
        UUID t1 = fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);
        UUID t2 = fixtures.technicianWithId(id(2, 2), dealer, service, vehicleType);

        CandidateResult result = select(bay);

        assertThat(result.outcome()).isEqualTo(Outcome.PAIRS);
        assertThat(result.pairs()).containsExactly(new Pair(bay, t1), new Pair(bay, t2));
    }

    @Test
    void atMostMaxPairsTechnicians() {
        UUID bay = fixtures.bay(id(1, 1), dealer, true, vehicleType);
        List<UUID> techs = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            techs.add(fixtures.technicianWithId(id(2, i), dealer, service, vehicleType));
        }

        // scheduler.booking.max-pairs = 3
        assertThat(select(bay).pairs()).extracting(Pair::technicianId).containsExactlyElementsOf(techs.subList(0, 3));
    }

    @Test
    void unknownBayIs404() {
        fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);
        UUID unknown = id(1, 9);

        assertThatThrownBy(() -> select(unknown))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Service bay " + unknown + " not found");
    }

    @Test
    void bayOfAnotherDealershipIs404() {
        UUID other = fixtures.dealership("UTC", "08:00", "17:00");
        UUID foreign = fixtures.bay(id(1, 1), other, true, vehicleType);
        fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);

        assertThatThrownBy(() -> select(foreign))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Service bay " + foreign + " not found");
    }

    @Test
    void bayForAnotherVehicleTypeIsNotServiceable() {
        UUID bay = fixtures.bay(id(1, 1), dealer, true, otherType);
        fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);

        assertThat(select(bay)).isEqualTo(CandidateResult.notServiceable());
    }

    @Test
    void inactiveBayIsNotServiceable() {
        UUID bay = fixtures.bay(id(1, 1), dealer, false, vehicleType);
        fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);

        assertThat(select(bay)).isEqualTo(CandidateResult.notServiceable());
    }

    @Test
    void noQualifiedTechnicianIsNotServiceable() {
        UUID bay = fixtures.bay(id(1, 1), dealer, true, vehicleType);
        // Skilled, but certified for another vehicle type only
        fixtures.technicianWithId(id(2, 1), dealer, service, otherType);

        assertThat(select(bay)).isEqualTo(CandidateResult.notServiceable());
    }

    @Test
    void busyBayIsNoneFreeEvenWithFreeTechnicians() {
        UUID bay = fixtures.bay(id(1, 1), dealer, true, vehicleType);
        fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);
        UUID otherTech = fixtures.technicianWithId(id(2, 2), dealer, service, vehicleType);
        appointment(bay, otherTech, "10:30", "11:30", "CONFIRMED");

        assertThat(select(bay)).isEqualTo(CandidateResult.noneFree());
    }

    @Test
    void everyTechnicianBusyIsNoneFree() {
        UUID bay = fixtures.bay(id(1, 1), dealer, true, vehicleType);
        UUID elsewhere = fixtures.bay(id(1, 2), dealer, true, vehicleType);
        UUID tech = fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);
        appointment(elsewhere, tech, "09:30", "10:30", "CONFIRMED");

        assertThat(select(bay)).isEqualTo(CandidateResult.noneFree());
    }

    @Test
    void busyTechnicianIsLeftOut() {
        UUID bay = fixtures.bay(id(1, 1), dealer, true, vehicleType);
        UUID elsewhere = fixtures.bay(id(1, 2), dealer, true, vehicleType);
        UUID busy = fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);
        UUID free = fixtures.technicianWithId(id(2, 2), dealer, service, vehicleType);
        appointment(elsewhere, busy, "10:00", "11:00", "CONFIRMED");

        assertThat(select(bay).pairs()).containsExactly(new Pair(bay, free));
    }

    @Test
    void cancelledAndBackToBackAppointmentsDoNotBlock() {
        UUID bay = fixtures.bay(id(1, 1), dealer, true, vehicleType);
        UUID tech = fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);
        appointment(bay, tech, "09:00", "10:00", "CONFIRMED");
        appointment(bay, tech, "10:00", "11:00", "CANCELLED");
        appointment(bay, tech, "11:00", "12:00", "CONFIRMED");

        assertThat(select(bay).pairs()).containsExactly(new Pair(bay, tech));
    }

    @Test
    void techniciansWithFewerJobsThatDayComeFirstThenById() {
        UUID bay = fixtures.bay(id(1, 1), dealer, true, vehicleType);
        UUID busyDay = fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);
        UUID quietB = fixtures.technicianWithId(id(2, 3), dealer, service, vehicleType);
        UUID quietA = fixtures.technicianWithId(id(2, 2), dealer, service, vehicleType);
        // Same local day, outside the window
        appointment(bay, busyDay, "08:00", "09:00", "CONFIRMED");
        // Cancelled jobs and jobs of another day do not count
        appointment(bay, quietA, "13:00", "14:00", "CANCELLED");
        fixtures.appointment(dealer, service, vehicleType, bay, quietB,
                OffsetDateTime.parse("2030-01-08T08:00:00Z"), OffsetDateTime.parse("2030-01-08T09:00:00Z"),
                "CONFIRMED");

        // 0 jobs: quietA < quietB by id; then busyDay with 1 job
        assertThat(select(bay).pairs()).containsExactly(
                new Pair(bay, quietA), new Pair(bay, quietB), new Pair(bay, busyDay));
    }

    private CandidateResult select(UUID bay) {
        return selector.select(dealer, service, vehicleType, bay, START);
    }

    private void appointment(UUID bay, UUID tech, String from, String to, String status) {
        fixtures.appointment(dealer, service, vehicleType, bay, tech,
                OffsetDateTime.parse("2030-01-07T" + from + ":00Z"), OffsetDateTime.parse("2030-01-07T" + to + ":00Z"),
                status);
    }

    /** Same prefix for every id of a test, so the order is decided by {@code kind} then {@code n}. */
    private UUID id(int kind, int n) {
        return UUID.fromString(prefix + "-0000-7000-800" + kind + "-" + String.format("%012d", n));
    }
}
