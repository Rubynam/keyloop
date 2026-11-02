package com.keyloop.scheduler.api.model.lookup;

import java.util.UUID;

/**
 * Id and display name of one free bay or technician. Shared by the lookup service and
 * repository.
 *
 * <pre>
 * service_bays row B1 → NamedResource(B1, "Bay 1")
 * </pre>
 */
public record NamedResource(UUID id, String name) {}
