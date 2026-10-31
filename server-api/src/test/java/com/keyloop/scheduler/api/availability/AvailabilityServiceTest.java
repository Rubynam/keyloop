package com.keyloop.scheduler.api.availability;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class AvailabilityServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 2);

    @Test
    void fourteenDaysInclusiveIsAllowed() {
        assertThatCode(() -> AvailabilityService.validateRange(FROM, FROM.plusDays(13))).doesNotThrowAnyException();
        assertThatCode(() -> AvailabilityService.validateRange(FROM, FROM)).doesNotThrowAnyException();
    }

    @Test
    void fifteenDaysIsRejected() {
        assertThatThrownBy(() -> AvailabilityService.validateRange(FROM, FROM.plusDays(14)))
                .isInstanceOf(InvalidRangeException.class);
    }

    @Test
    void toBeforeFromIsRejected() {
        assertThatThrownBy(() -> AvailabilityService.validateRange(FROM, FROM.minusDays(1)))
                .isInstanceOf(InvalidRangeException.class);
    }
}
