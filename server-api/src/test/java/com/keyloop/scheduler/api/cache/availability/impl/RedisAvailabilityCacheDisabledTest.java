package com.keyloop.scheduler.api.cache.availability.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.keyloop.scheduler.api.config.availability.AvailabilityCacheProperties;
import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse.Slot;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.json.JsonMapper;

/** Cache switched off: every date comes from the loader and Redis is never touched. */
class RedisAvailabilityCacheDisabledTest {

    private static final LocalDate DAY_1 = LocalDate.of(2030, 1, 7);
    private static final LocalDate DAY_2 = LocalDate.of(2030, 1, 8);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    @SuppressWarnings("unchecked")
    void disabledCacheReturnsTheLoaderResultWithoutRedis() {
        AvailabilityCacheProperties off = new AvailabilityCacheProperties(false, Duration.ZERO, Duration.ZERO,
                Duration.ZERO, Duration.ZERO, Duration.ZERO);
        RedisAvailabilityCache cache = new RedisAvailabilityCache(redis, mock(RedisScript.class),
                JsonMapper.builder().build(), off, ObservationRegistry.NOOP);
        Slot slot = new Slot(OffsetDateTime.parse("2030-01-07T09:00Z"), OffsetDateTime.parse("2030-01-07T10:00Z"));
        Map<LocalDate, List<Slot>> loaded = Map.of(DAY_1, List.of(slot), DAY_2, List.of());
        Map<Set<LocalDate>, Integer> calls = new HashMap<>();

        Map<LocalDate, List<Slot>> result = cache
                .lookup(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(DAY_1, DAY_2))
                .slots(dates -> {
                    calls.merge(Set.copyOf(dates), 1, Integer::sum);
                    return loaded;
                });

        assertThat(result).isEqualTo(loaded);
        assertThat(calls).containsExactly(Map.entry(Set.of(DAY_1, DAY_2), 1));
        verifyNoInteractions(redis);
    }
}
