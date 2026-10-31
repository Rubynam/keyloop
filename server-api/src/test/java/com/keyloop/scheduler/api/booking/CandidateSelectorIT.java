package com.keyloop.scheduler.api.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.keyloop.scheduler.api.booking.CandidateResult.Outcome;
import com.keyloop.scheduler.api.booking.CandidateResult.Pair;
import com.keyloop.scheduler.api.support.Fixtures;
import com.keyloop.scheduler.api.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * spec-availability-ops CAP-7: every row of the candidate-selection.md examples table on
 * real PostgreSQL, plus the two ordering rules. Ids are chosen by the test (same prefix,
 * rising last digit) so "B1 < B2 < B3" is also PostgreSQL's {@code id} order. Each test
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

    @ParameterizedTest(name = "{0} bays, {1} techs → {2}")
    @CsvSource({
            "3, 4, 'B1T1 B2T2 B3T3'",
            "1, 3, 'B1T1 B1T2 B1T3'",
            "2, 1, 'B1T1 B2T1'",
            "2, 2, 'B1T1 B2T2 B1T2'",
    })
    void examplesTablePairs(int bayCount, int techCount, String expected) {
        List<UUID> bays = new ArrayList<>();
        for (int i = 1; i <= bayCount; i++) {
            bays.add(fixtures.bay(id(1, i), dealer, true, vehicleType));
        }
        List<UUID> techs = new ArrayList<>();
        for (int i = 1; i <= techCount; i++) {
            techs.add(fixtures.technicianWithId(id(2, i), dealer, service, vehicleType));
        }

        CandidateResult result = select();

        assertThat(result.outcome()).isEqualTo(Outcome.PAIRS);
        assertThat(result.pairs()).containsExactlyElementsOf(Arrays.stream(expected.split(" "))
                .map(p -> new Pair(bays.get(p.charAt(1) - '1'), techs.get(p.charAt(3) - '1')))
                .toList());
    }

    @Test
    void examplesTableNoFittingBayIsNotServiceable() {
        fixtures.bay(id(1, 1), dealer, true, otherType);
        fixtures.bay(id(1, 2), dealer, false, vehicleType);
        fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);

        assertThat(select()).isEqualTo(CandidateResult.notServiceable());
    }

    @Test
    void noQualifiedTechnicianIsNotServiceable() {
        fixtures.bay(id(1, 1), dealer, true, vehicleType);
        // Skilled, but certified for another vehicle type only
        fixtures.technicianWithId(id(2, 1), dealer, service, otherType);

        assertThat(select()).isEqualTo(CandidateResult.notServiceable());
    }

    @Test
    void examplesTableBusyBayIsNoneFree() {
        UUID bay = fixtures.bay(id(1, 1), dealer, true, vehicleType);
        fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);
        UUID otherTech = fixtures.technicianWithId(id(2, 2), dealer, service, vehicleType);
        appointment(bay, otherTech, "10:30", "11:30", "CONFIRMED");

        assertThat(select()).isEqualTo(CandidateResult.noneFree());
    }

    @Test
    void cancelledAndBackToBackAppointmentsDoNotBlock() {
        UUID bay = fixtures.bay(id(1, 1), dealer, true, vehicleType);
        UUID tech = fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);
        appointment(bay, tech, "09:00", "10:00", "CONFIRMED");
        appointment(bay, tech, "10:00", "11:00", "CANCELLED");
        appointment(bay, tech, "11:00", "12:00", "CONFIRMED");

        assertThat(select().pairs()).containsExactly(new Pair(bay, tech));
    }

    @Test
    void baysTakingFewerVehicleTypesComeFirst() {
        // Lower id, but it also takes another type: keep it for vehicles only it can take
        UUID flexible = fixtures.bay(id(1, 1), dealer, true, vehicleType, otherType);
        UUID single = fixtures.bay(id(1, 2), dealer, true, vehicleType);
        UUID tech = fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);

        assertThat(select().pairs()).containsExactly(new Pair(single, tech), new Pair(flexible, tech));
    }

    @Test
    void techniciansWithFewerJobsThatDayComeFirst() {
        UUID bay = fixtures.bay(id(1, 1), dealer, true, vehicleType);
        UUID busyDay = fixtures.technicianWithId(id(2, 1), dealer, service, vehicleType);
        UUID quietDay = fixtures.technicianWithId(id(2, 2), dealer, service, vehicleType);
        // Same local day, outside the window
        appointment(bay, busyDay, "08:00", "09:00", "CONFIRMED");
        // Cancelled jobs do not count
        appointment(bay, quietDay, "13:00", "14:00", "CANCELLED");

        assertThat(select().pairs()).containsExactly(new Pair(bay, quietDay), new Pair(bay, busyDay));
    }

    private CandidateResult select() {
        return selector.select(dealer, service, vehicleType, START);
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
