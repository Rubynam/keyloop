package com.keyloop.scheduler.api.model.appointment;

/**
 * Display names of the things one appointment points to. The service puts them next to
 * the ids in the response, so a person can read it without looking ids up.
 *
 * <pre>
 * (D1, B2, T2, OIL, V1) → AppointmentNames("Keyloop Hub", "Bay 2", "An Nguyen",
 *                                         "Oil change", "TOYOTA Vios 2022")
 * </pre>
 */
public record AppointmentNames(
        String dealershipName,
        String serviceBayName,
        String technicianName,
        String serviceTypeName,
        String vehicleName) {}
