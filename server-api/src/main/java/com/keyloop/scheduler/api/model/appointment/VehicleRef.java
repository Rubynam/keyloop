package com.keyloop.scheduler.api.model.appointment;

import java.util.UUID;

/**
 * The two facts a booking needs about a vehicle: who owns it and its type. The type
 * picks the bays and technicians; the client never sends it. Spec07 §4.
 *
 * <pre>
 * vehicles row V1 → VehicleRef(V1, customer C1, type SUV)
 * </pre>
 */
public record VehicleRef(UUID id, UUID customerId, UUID vehicleTypeId) {}
