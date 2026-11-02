package com.keyloop.scheduler.api.dto.lookup;

/**
 * Why no pair can work in the asked window, whatever bay or technician is picked.
 * Sent as text in {@code reasons}; an empty list means the window itself is fine.
 *
 * <pre>
 * 07:00-08:00, open 08:00          → [OUTSIDE_BUSINESS_HOURS]
 * 09:00-09:30, oil change 60 min   → [SHORTER_THAN_SERVICE]
 * </pre>
 */
public enum WindowProblem {
    /** The window starts before now. */
    STARTS_IN_PAST,
    /** The window starts before local opening or ends after local closing. */
    OUTSIDE_BUSINESS_HOURS,
    /** The window is shorter than the asked service type. */
    SHORTER_THAN_SERVICE
}
