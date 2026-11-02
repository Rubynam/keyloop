package com.keyloop.scheduler.api.model.availability;

/**
 * Name and business hours of one dealership, as minutes after local midnight;
 * {@code closeMinute} may be 1440 ('24:00'). Shared by the availability service and repository.
 *
 * @param timezone IANA zone id, e.g. {@code Asia/Ho_Chi_Minh}
 */
public record Dealership(String name, String timezone, int openMinute, int closeMinute) {}
