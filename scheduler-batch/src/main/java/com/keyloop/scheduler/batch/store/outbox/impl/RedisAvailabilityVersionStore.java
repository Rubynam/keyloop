package com.keyloop.scheduler.batch.store.outbox.impl;

import java.time.LocalDate;
import java.util.UUID;

import com.keyloop.scheduler.batch.config.outbox.OutboxRelayProperties;
import com.keyloop.scheduler.batch.constant.outbox.RelayConstants;
import com.keyloop.scheduler.batch.store.outbox.AvailabilityVersionStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** {@link AvailabilityVersionStore} on Redis: {@code INCR} then {@code EXPIRE versionKeyTtl}. */
@Component
public class RedisAvailabilityVersionStore implements AvailabilityVersionStore {

    private final StringRedisTemplate redis;
    private final OutboxRelayProperties properties;

    public RedisAvailabilityVersionStore(StringRedisTemplate redis, OutboxRelayProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void bump(UUID dealershipId, LocalDate date) {
        String key = RelayConstants.VERSION_KEY_PREFIX + dealershipId + RelayConstants.KEY_SEPARATOR + date;
        redis.opsForValue().increment(key);
        redis.expire(key, properties.versionKeyTtl());
    }
}
