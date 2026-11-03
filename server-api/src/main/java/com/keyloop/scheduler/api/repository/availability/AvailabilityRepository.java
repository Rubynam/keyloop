package com.keyloop.scheduler.api.repository.availability;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.keyloop.scheduler.api.model.availability.Booking;
import com.keyloop.scheduler.api.model.availability.Dealership;
import com.keyloop.scheduler.api.model.availability.ServiceBay;
import com.keyloop.scheduler.api.model.availability.ServiceType;
import com.keyloop.scheduler.api.model.availability.Technician;

/**
 * Read-only data access for the availability query and candidate selection. It gives
 * the service the hours, durations, fitting resources and bookings it needs to find
 * free slots. It never writes. spec-read-availability, DLD §8.
 *
 * <pre>
 * findDealership(D1)                        → Dealership("Hub", "UTC", 480, 1020)
 * findServiceType(OIL)                      → ServiceType("Oil change", 60)
 * findVehicleTypeName(SUV)                  → "SUV"
 * findServiceBay(D1, B1, SUV)               → ServiceBay("Bay 1", true, true)
 * findFittingBayIds(D1, SUV)                → [B1, B2]
 * findQualifiedTechnicianIds(D1, OIL, SUV)  → [T1]
 * findQualifiedTechnicians(D1, OIL, SUV)    → [Technician(T1, "An")]
 * findBookings(D1, [B1,B2], [T1], 09:00, 17:00)
 *                                           → [Booking(B1, T1, 10:00, 11:00)]
 * </pre>
 */
public interface AvailabilityRepository {

    /**
     * Name, business hours and zone of one dealership. Seconds round inwards (open up, close
     * down), so a slot never starts before open or ends after close.
     *
     * <pre>
     * open 08:00:30, close 17:00 → Dealership(name, tz, 481, 1020);  close '24:00' → 1440
     * </pre>
     */
    Optional<Dealership> findDealership(UUID id);

    /**
     * Name and length (minutes) of one service type; empty when the id is unknown.
     *
     * <pre>
     * OIL_CHANGE → Optional[ServiceType("Oil change", 60)];  unknown → Optional.empty
     * </pre>
     */
    Optional<ServiceType> findServiceType(UUID serviceTypeId);

    /**
     * Display name of the vehicle type; empty when the id is unknown, so the service
     * can answer 404.
     *
     * <pre>
     * SUV → Optional["SUV"];  unknown → Optional.empty
     * </pre>
     */
    Optional<String> findVehicleTypeName(UUID vehicleTypeId);

    /**
     * One bay of the dealership, with its name and whether it fits the vehicle type.
     * Empty when the bay is unknown or belongs to another dealership, so the service
     * can answer 404.
     *
     * <pre>
     * (D1, B1, SUV) → Optional[ServiceBay("Bay 1", true, true)]
     * (D1, B4, SUV) → Optional[ServiceBay("Car bay", true, false)]   car-only bay
     * (D1, B9, SUV) → Optional.empty                                  bay of D2
     * </pre>
     */
    Optional<ServiceBay> findServiceBay(UUID dealershipId, UUID serviceBayId, UUID vehicleTypeId);

    /**
     * Active bays of the dealership that take the vehicle type.
     *
     * <pre>
     * (D1, SUV) → [B1, B2]     inactive B3 and car-only B4 are left out
     * </pre>
     */
    List<UUID> findFittingBayIds(UUID dealershipId, UUID vehicleTypeId);

    /**
     * Active technicians of the dealership with the skill AND the vehicle-type
     * certification.
     *
     * <pre>
     * (D1, OIL, SUV) → [T1]    T2 has the skill but no SUV certificate
     * </pre>
     */
    List<UUID> findQualifiedTechnicianIds(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId);

    /**
     * Same technicians as {@link #findQualifiedTechnicianIds}, with their names. The
     * availability read uses it to name the technician of each free slot.
     *
     * <pre>
     * (D1, OIL, SUV) → [Technician(T1, "An")]    T2 has no SUV certificate
     * </pre>
     */
    List<Technician> findQualifiedTechnicians(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId);

    /**
     * Non-cancelled appointments that hold one of the given bays or technicians and
     * overlap {@code [from, to)} (half-open, D1). Both collections must be non-empty.
     *
     * <pre>
     * window 09:00 ──────────────── 12:00
     *        [10:00─11:00] CONFIRMED   → returned
     *        [11:00─12:00] CANCELLED   → skipped
     *        [12:00─13:00] CONFIRMED   → skipped (touches the end only)
     * </pre>
     */
    List<Booking> findBookings(UUID dealershipId, Collection<UUID> bayIds, Collection<UUID> technicianIds,
                               Instant from, Instant to);
}
