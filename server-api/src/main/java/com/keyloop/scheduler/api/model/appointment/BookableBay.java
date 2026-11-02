package com.keyloop.scheduler.api.model.appointment;

import java.util.UUID;

/**
 * What a booking needs to know about the bay the client chose: does it work now, and
 * does it take this vehicle. A bay of another dealership is never returned, so the
 * service can answer 404 for it.
 *
 * <pre>
 * (D1, B1, SUV) → BookableBay(B1, active=true,  takesVehicleType=true)   bookable
 * (D1, B3, SUV) → BookableBay(B3, active=false, takesVehicleType=true)   422
 * (D1, B9, SUV) → empty  (B9 is unknown or belongs to D2)                404
 * </pre>
 */
public record BookableBay(UUID id, boolean active, boolean takesVehicleType) {

    /** Active and fits the vehicle type: the only case a booking may use it. */
    public boolean bookable() {
        return active && takesVehicleType;
    }
}
