package com.keyloop.scheduler.api.domain;

/**
 * Resource pool a vehicle, a service bay and a technician certification belong to (D2).
 * Stored by name in VARCHAR columns; the DB has no CHECK list, so a new pool is added here only.
 */
public enum VehicleType {
    PRIVATE_CAR,
    TRUCK,
    EV
}
