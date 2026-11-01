package com.keyloop.scheduler.api.support;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Own fixture rows per test, never the shared seed data. Every call makes new rows with
 * unique codes, so tests do not see each other and need no cleanup.
 */
public final class Fixtures {

    private final JdbcClient jdbc;

    public Fixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID dealership(String timezone, String open, String close) {
        return id("INSERT INTO dealerships (name, timezone, open_time, close_time) VALUES (?, ?, ?::time, ?::time)"
                + " RETURNING id", "IT " + code("D"), timezone, open, close);
    }

    public UUID vehicleType() {
        return id("INSERT INTO vehicle_types (code, name) VALUES (?, 'IT type') RETURNING id", code("VT"));
    }

    public UUID serviceType(int minutes) {
        return id("INSERT INTO service_types (code, name, duration_minutes) VALUES (?, 'IT service', ?) RETURNING id",
                code("ST"), minutes);
    }

    /** Active bay with a database-made id. */
    public UUID bay(UUID dealership, UUID... vehicleTypes) {
        return bay(id("SELECT uuidv7()"), dealership, true, vehicleTypes);
    }

    public UUID bay(UUID id, UUID dealership, boolean active, UUID... vehicleTypes) {
        jdbc.sql("INSERT INTO service_bays (id, dealership_id, name, active) VALUES (?, ?, ?, ?)")
                .params(id, dealership, "Bay " + code("B"), active).update();
        for (UUID type : vehicleTypes) {
            jdbc.sql("INSERT INTO service_bay_vehicle_types (service_bay_id, vehicle_type_id) VALUES (?, ?)")
                    .params(id, type).update();
        }
        return id;
    }

    /** Active technician with the skill and the certifications, database-made id. */
    public UUID technician(UUID dealership, UUID serviceType, UUID... vehicleTypes) {
        return technicianWithId(id("SELECT uuidv7()"), dealership, serviceType, vehicleTypes);
    }

    public UUID technicianWithId(UUID id, UUID dealership, UUID serviceType, UUID... vehicleTypes) {
        jdbc.sql("INSERT INTO technicians (id, dealership_id, name) VALUES (?, ?, ?)")
                .params(id, dealership, "Tech " + code("T")).update();
        jdbc.sql("INSERT INTO technician_skills (technician_id, service_type_id) VALUES (?, ?)")
                .params(id, serviceType).update();
        for (UUID type : vehicleTypes) {
            jdbc.sql("INSERT INTO technician_vehicle_types (technician_id, vehicle_type_id) VALUES (?, ?)")
                    .params(id, type).update();
        }
        return id;
    }

    public UUID customer() {
        return id("INSERT INTO customers (name, email) VALUES ('IT customer', ?) RETURNING id",
                code("c").toLowerCase() + "@example.com");
    }

    /** A vehicle of a new car model with the given type, owned by {@code customer}. */
    public UUID vehicle(UUID customer, UUID vehicleType) {
        UUID carInfo = id("INSERT INTO car_info (name, vehicle_type_id, brand) VALUES (?, ?, 'IT') RETURNING id",
                code("M"), vehicleType);
        return id("INSERT INTO vehicles (customer_id, car_info_id, vehicle_type_id, vin, year)"
                + " VALUES (?, ?, ?, ?, 2024) RETURNING id", customer, carInfo, vehicleType, vin());
    }

    /** An appointment of a new customer and vehicle, so the vehicle exclusion never gets in the way. */
    public UUID appointment(UUID dealership, UUID serviceType, UUID vehicleType, UUID bay, UUID technician,
                            OffsetDateTime start, OffsetDateTime end, String status) {
        UUID customer = customer();
        return appointment(customer, vehicle(customer, vehicleType), dealership, serviceType, vehicleType, bay,
                technician, start, end, status);
    }

    /** An appointment of a known customer and vehicle. */
    public UUID appointment(UUID customer, UUID vehicle, UUID dealership, UUID serviceType, UUID vehicleType,
                            UUID bay, UUID technician, OffsetDateTime start, OffsetDateTime end, String status) {
        return id("""
                        INSERT INTO appointments (customer_id, vehicle_id, dealership_id, service_type_id, technician_id,
                                                  service_bay_id, vehicle_type_id, start_time, end_time, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """,
                customer, vehicle, dealership, serviceType, technician, bay, vehicleType, start, end, status);
    }

    private UUID id(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(UUID.class).single();
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
