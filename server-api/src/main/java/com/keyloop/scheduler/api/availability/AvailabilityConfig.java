package com.keyloop.scheduler.api.availability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/** Beans of the availability read path: the lock release script. The clock lives in {@link com.keyloop.scheduler.api.config.ClockConfig}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AvailabilityCacheProperties.class)
public class AvailabilityConfig {

    /**
     * Deletes the lock only when it still holds our token. See {@link AvailabilityCache}
     * for why the check and the delete must be one step.
     */
    static final String RELEASE_LOCK_LUA = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    @Bean
    RedisScript<Long> availabilityLockReleaseScript() {
        return new DefaultRedisScript<>(RELEASE_LOCK_LUA, Long.class);
    }
}
