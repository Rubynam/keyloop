package com.keyloop.scheduler.api.config.availability;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import com.keyloop.scheduler.api.constant.availability.AvailabilityConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Slot grid shared by the availability read and the booking check (Spec07 D4). The
 * customer can book exactly the starts that availability shows. A bad step fails at
 * startup, not on the first request.
 *
 * <pre>
 * scheduler.availability.slot-step: 15m   open 08:00 → starts 08:00, 08:15, 08:30, ...
 * slot-step: 0m or 7m                     → boot fails (must be &gt; 0 and divide 24h)
 * </pre>
 *
 * @param slotStep distance between two slot starts, from the opening time
 */
@Validated
@ConfigurationProperties("scheduler.availability")
public record AvailabilityProperties(@NotNull @DefaultValue("15m") Duration slotStep) {

    public AvailabilityProperties {
        if (slotStep != null && !validStep(slotStep)) {
            throw new IllegalArgumentException(AvailabilityConstants.INVALID_SLOT_STEP.formatted(slotStep));
        }
    }

    /** The step in whole minutes, e.g. {@code 15m → 15}. */
    public int stepMinutes() {
        return Math.toIntExact(slotStep.toMinutes());
    }

    private static boolean validStep(Duration step) {
        long minutes = step.toMinutes();
        // Whole minutes only, and every day has the same grid
        return minutes > 0
                && Duration.ofMinutes(minutes).equals(step)
                && Duration.ofDays(1).toMinutes() % minutes == 0;
    }
}
