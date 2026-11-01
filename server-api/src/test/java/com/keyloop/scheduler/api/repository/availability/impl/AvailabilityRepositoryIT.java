package com.keyloop.scheduler.api.repository.availability.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse;
import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse.Slot;
import com.keyloop.scheduler.api.model.availability.Booking;
import com.keyloop.scheduler.api.model.availability.Dealership;
import com.keyloop.scheduler.api.repository.availability.AvailabilityRepository;
import com.keyloop.scheduler.api.service.availability.impl.AvailabilityServiceImpl;
import com.keyloop.scheduler.api.support.IntegrationTest;
import com.keyloop.scheduler.api.support.PassThroughAvailabilityCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the availability SQL on real Postgres (Flyway schema): cancelled vs confirmed,
 * half-open overlap, inactive bay, technician without the vehicle-type certification,
 * {@code close_time = '24:00'}. Fixed far-future day and fixed clock: date-independent.
 * Each test rolls back.
 */
@Transactional
class AvailabilityRepositoryIT extends IntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2030, 1, 7);
    private static final Clock LONG_AGO = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AvailabilityRepository repository;

    private UUID dealer;
    private UUID service;
    private UUID vehicleType;
    private UUID fittingBay;
    private UUID inactiveBay;
    private UUID qualifiedTech;
    private UUID uncertifiedTech;

    @BeforeEach
    void seed() {
        dealer = id("INSERT INTO dealerships (name, timezone, open_time, close_time)"
                + " VALUES ('IT 24h', 'UTC', '00:00', '24:00') RETURNING id");
        vehicleType = id("INSERT INTO vehicle_types (code, name) VALUES ('" + code("VT") + "', 'IT car') RETURNING id");
        UUID otherType = id("INSERT INTO vehicle_types (code, name) VALUES ('" + code("VT") + "', 'IT other') RETURNING id");
        service = id("INSERT INTO service_types (code, name, duration_minutes) VALUES ('" + code("ST") + "', 'IT 60', 60) RETURNING id");

        fittingBay = bay("Bay A", true, vehicleType);
        inactiveBay = bay("Bay inactive", false, vehicleType);
        bay("Bay other type", true, otherType);

        qualifiedTech = technician("Tech qualified", vehicleType);
        uncertifiedTech = technician("Tech no cert", otherType);

        UUID customer = id("INSERT INTO customers (name, email) VALUES ('IT customer', '" + code("it") + "@example.com') RETURNING id");
        UUID carInfo = id("INSERT INTO car_info (name, vehicle_type_id, brand) VALUES ('" + code("M") + "', '"
                + vehicleType + "', 'IT') RETURNING id");
        UUID vehicle = id("INSERT INTO vehicles (customer_id, car_info_id, vehicle_type_id, vin, year) VALUES ('"
                + customer + "', '" + carInfo + "', '" + vehicleType + "', '" + vin() + "', 2024) RETURNING id");

        appointment(customer, vehicle, "09:00", "10:00", "CONFIRMED");
        appointment(customer, vehicle, "12:00", "13:00", "CANCELLED");
    }

    @Test
    void repositoryKeepsOnlyActiveFittingBaysAndCertifiedTechnicians() {
        assertThat(repository.findFittingBayIds(dealer, vehicleType)).containsExactly(fittingBay).doesNotContain(inactiveBay);
        assertThat(repository.findQualifiedTechnicianIds(dealer, service, vehicleType))
                .containsExactly(qualifiedTech).doesNotContain(uncertifiedTech);
    }

    @Test
    void repositoryReadsHoursAndSkipsCancelledBookings() {
        assertThat(repository.findDealership(dealer)).get()
                .isEqualTo(new Dealership("UTC", 0, 1440));

        List<Booking> bookings = repository.findBookings(dealer, List.of(fittingBay),
                List.of(qualifiedTech), Instant.parse(DAY + "T00:00:00Z"), Instant.parse(DAY.plusDays(1) + "T00:00:00Z"));
        assertThat(bookings).singleElement().satisfies(b -> {
            assertThat(b.start()).isEqualTo(Instant.parse(DAY + "T09:00:00Z"));
            assertThat(b.end()).isEqualTo(Instant.parse(DAY + "T10:00:00Z"));
        });
    }

    @Test
    void hoursWithSecondsRoundInwards() {
        UUID odd = id("INSERT INTO dealerships (name, timezone, open_time, close_time)"
                + " VALUES ('IT odd', 'UTC', '08:00:30', '16:59:30') RETURNING id");

        assertThat(repository.findDealership(odd)).get()
                .isEqualTo(new Dealership("UTC", 8 * 60 + 1, 16 * 60 + 59));
    }

    @Test
    void serviceBlocksConfirmedOffersCancelledBackToBackAndMidnightEnd() {
        AvailabilityResponse response = new AvailabilityServiceImpl(repository, new PassThroughAvailabilityCache(), LONG_AGO)
                .find(dealer, service, vehicleType, DAY, DAY);

        assertThat(response.timezone()).isEqualTo("UTC");
        assertThat(response.durationMinutes()).isEqualTo(60);
        List<Slot> slots = response.days().getFirst().slots();
        List<String> starts = slots.stream().map(s -> s.start().toLocalTime().toString()).toList();

        // Confirmed 09:00-10:00 on the only fitting bay + certified technician; the inactive
        // bay and the uncertified technician would otherwise make 09:00 free
        assertThat(starts).doesNotContain("08:15", "08:45", "09:00", "09:45");
        // Half-open: ending at 09:00 and starting at 10:00 are fine
        assertThat(starts).contains("08:00", "10:00");
        // CANCELLED 12:00-13:00 does not block
        assertThat(starts).contains("12:00", "12:15");
        Slot last = slots.getLast();
        assertThat(last.start()).isEqualTo(OffsetDateTime.parse(DAY + "T23:00:00Z"));
        assertThat(last.end()).isEqualTo(OffsetDateTime.parse(DAY.plusDays(1) + "T00:00:00Z"));
    }

    private UUID bay(String name, boolean active, UUID type) {
        UUID bay = id("INSERT INTO service_bays (dealership_id, name, active) VALUES ('" + dealer + "', '"
                + name + "', " + active + ") RETURNING id");
        jdbc.sql("INSERT INTO service_bay_vehicle_types (service_bay_id, vehicle_type_id) VALUES (?, ?)")
                .params(bay, type).update();
        return bay;
    }

    private UUID technician(String name, UUID certifiedType) {
        UUID tech = id("INSERT INTO technicians (dealership_id, name) VALUES ('" + dealer + "', '" + name + "') RETURNING id");
        jdbc.sql("INSERT INTO technician_skills (technician_id, service_type_id) VALUES (?, ?)").params(tech, service).update();
        jdbc.sql("INSERT INTO technician_vehicle_types (technician_id, vehicle_type_id) VALUES (?, ?)")
                .params(tech, certifiedType).update();
        return tech;
    }

    private void appointment(UUID customer, UUID vehicle, String from, String to, String status) {
        jdbc.sql("""
                        INSERT INTO appointments (customer_id, vehicle_id, dealership_id, service_type_id, technician_id,
                                                  service_bay_id, vehicle_type_id, start_time, end_time, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(customer, vehicle, dealer, service, qualifiedTech, fittingBay, vehicleType,
                        OffsetDateTime.parse(DAY + "T" + from + ":00Z"), OffsetDateTime.parse(DAY + "T" + to + ":00Z"),
                        status)
                .update();
    }

    private UUID id(String sql) {
        return jdbc.sql(sql).query(UUID.class).single();
    }

    private static String code(String prefix) {
        // vehicle_types.code is VARCHAR(32)
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }

    private static String vin() {
        StringBuilder vin = new StringBuilder();
        for (int i = 0; i < 17; i++) {
            vin.append(ThreadLocalRandom.current().nextInt(10));
        }
        return vin.toString();
    }
}
