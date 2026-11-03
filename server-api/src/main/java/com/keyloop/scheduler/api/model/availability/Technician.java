package com.keyloop.scheduler.api.model.availability;

import java.util.UUID;

/**
 * Id and display name of one qualified technician. Shared by the availability service
 * and repository. The service names the technician of each free slot with it.
 *
 * <pre>
 * technicians row T1 → Technician(T1, "Nguyen Van A")
 * </pre>
 */
public record Technician(UUID id, String name) {}
