package com.keyloop.scheduler.api.model;

/**
 * Appointment lifecycle (DLD §9). Stored by name in appointments.status.
 * Note: the exclusion constraints in V2 compare against 'CANCELLED' by name.
 */
public enum AppointmentStatus {
    CONFIRMED,
    CANCELLED,
    COMPLETED
}
