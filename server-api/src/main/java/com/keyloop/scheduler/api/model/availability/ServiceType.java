package com.keyloop.scheduler.api.model.availability;

/**
 * Display name and length of one service type. Shared by the availability service,
 * candidate selection and the repository.
 *
 * <pre>
 * service_types row OIL → ServiceType("Oil change", 60)
 * </pre>
 */
public record ServiceType(String name, int durationMinutes) {}
