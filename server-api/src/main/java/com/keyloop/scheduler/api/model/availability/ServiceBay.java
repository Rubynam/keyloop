package com.keyloop.scheduler.api.model.availability;

/**
 * Name and fit of one service bay for one vehicle type. Shared by the availability
 * service and repository. A bay that is inactive or does not take the vehicle type
 * gives no slots.
 *
 * <pre>
 * service_bays row B1 (active, takes SUV) → ServiceBay("Bay 1", true, true)
 * </pre>
 */
public record ServiceBay(String name, boolean active, boolean takesVehicleType) {

    /** True when this bay can serve the vehicle type of the query. */
    public boolean fits() {
        return active && takesVehicleType;
    }
}
