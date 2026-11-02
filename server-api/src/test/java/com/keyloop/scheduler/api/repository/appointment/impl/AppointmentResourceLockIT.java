package com.keyloop.scheduler.api.repository.appointment.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.keyloop.scheduler.api.exception.appointment.SlotTakenException.Resource;
import com.keyloop.scheduler.api.repository.appointment.AppointmentRepository;
import com.keyloop.scheduler.api.support.Fixtures;
import com.keyloop.scheduler.api.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lock → check-again step of a booking try on real PostgreSQL: tries that want the
 * same technician, bay or vehicle wait in line; others do not; the check names the busy
 * resource, vehicle first. Each test makes its own dealership, so nothing needs cleanup.
 */
class AppointmentResourceLockIT extends IntegrationTest {

    /** 09:00–10:00 UTC on a far-future day. */
    private static final OffsetDateTime NINE = OffsetDateTime.parse("2030-01-07T09:00:00Z");
    private static final OffsetDateTime TEN = NINE.plusHours(1);
    private static final long WAIT_MS = 300;

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AppointmentRepository appointments;
    @Autowired
    private TransactionTemplate transactions;

    private Fixtures fixtures;
    private UUID dealer;
    private UUID service;
    private UUID vehicleType;

    @BeforeEach
    void seed() {
        fixtures = new Fixtures(jdbc);
        dealer = fixtures.dealership("UTC", "08:00", "17:00");
        service = fixtures.serviceType(60);
        vehicleType = fixtures.vehicleType();
    }

    @Test
    void findBusyResourceNamesTheVehicleFirstThenTechnicianThenBay() {
        UUID bay = fixtures.bay(dealer, vehicleType);
        UUID otherBay = fixtures.bay(dealer, vehicleType);
        UUID tech = fixtures.technician(dealer, service, vehicleType);
        UUID otherTech = fixtures.technician(dealer, service, vehicleType);
        UUID customer = fixtures.customer();
        UUID car = fixtures.vehicle(customer, vehicleType);
        UUID otherCar = fixtures.vehicle(customer, vehicleType);
        fixtures.appointment(customer, car, dealer, service, vehicleType, bay, tech, NINE, TEN, "CONFIRMED");

        assertThat(busy(tech, bay, car, NINE.plusMinutes(30), TEN.plusMinutes(30))).contains(Resource.VEHICLE);
        assertThat(busy(tech, otherBay, otherCar, NINE, TEN)).contains(Resource.TECHNICIAN);
        assertThat(busy(otherTech, bay, otherCar, NINE, TEN)).contains(Resource.BAY);
        // Back-to-back is fine
        assertThat(busy(tech, bay, car, TEN, TEN.plusHours(1))).isEmpty();
    }

    @Test
    void cancelledAppointmentsAreNotBusy() {
        UUID bay = fixtures.bay(dealer, vehicleType);
        UUID tech = fixtures.technician(dealer, service, vehicleType);
        fixtures.appointment(dealer, service, vehicleType, bay, tech, NINE, TEN, "CANCELLED");

        assertThat(busy(tech, bay, UUID.randomUUID(), NINE, TEN)).isEmpty();
    }

    @Test
    void secondTryOnTheSameTechnicianWaitsUntilTheFirstCommits() throws Exception {
        UUID tech = fixtures.technician(dealer, service, vehicleType);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> first = holdLocks(tech, UUID.randomUUID(), UUID.randomUUID(), locked, release);
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> second = lockAndCommit(tech, UUID.randomUUID(), UUID.randomUUID());

        Thread.sleep(WAIT_MS);
        assertThat(second).isNotDone();
        release.countDown();
        second.get(5, TimeUnit.SECONDS);
        first.get(5, TimeUnit.SECONDS);
    }

    @Test
    void triesOnOtherResourcesDoNotWait() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> first = holdLocks(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), locked, release);
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        lockAndCommit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).get(WAIT_MS, TimeUnit.MILLISECONDS);

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
    }

    @Test
    void crossedPairsDoNotDeadlock() throws Exception {
        // Try A wants (T1, B2), try B wants (T2, B1): same kinds, fixed order → no cycle
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> first = holdLocks(t1, b2, UUID.randomUUID(), locked, release);
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        lockAndCommit(t2, b1, UUID.randomUUID()).get(WAIT_MS, TimeUnit.MILLISECONDS);

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
    }

    private Optional<Resource> busy(UUID tech, UUID bay, UUID car, OffsetDateTime from, OffsetDateTime to) {
        return appointments.findBusyResource(tech, bay, car, from.toInstant(), to.toInstant());
    }

    /** Takes the three locks in its own transaction and keeps them until {@code release}. */
    private CompletableFuture<Void> holdLocks(UUID tech, UUID bay, UUID car, CountDownLatch locked,
                                              CountDownLatch release) {
        return CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            appointments.lockResources(tech, bay, car);
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private CompletableFuture<Void> lockAndCommit(UUID tech, UUID bay, UUID car) {
        return CompletableFuture.runAsync(() -> transactions.executeWithoutResult(
                status -> appointments.lockResources(tech, bay, car)));
    }
}
