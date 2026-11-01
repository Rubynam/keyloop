package com.keyloop.scheduler.api.model.availability;

import java.time.Instant;
import java.util.UUID;

/** One non-cancelled appointment holding a bay and a technician over {@code [start, end)}. */
public record Booking(UUID serviceBayId, UUID technicianId, Instant start, Instant end) {}
