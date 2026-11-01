package com.keyloop.scheduler.api.config.availability;

import com.keyloop.scheduler.api.constant.availability.AvailabilityConstants;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Beans of the availability read path: the slot grid, the cache timings and the lock
 * release script.
 * The clock lives in {@link com.keyloop.scheduler.api.config.ClockConfig}. Spec05 RF-10.
 *
 * <pre>
 * classpath:redis/release-lock.lua  ──►  bean availabilityLockReleaseScript  ──►  RedisAvailabilityCache
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AvailabilityCacheProperties.class, AvailabilityProperties.class})
public class AvailabilityConfig {

    /**
     * Deletes the lock only when it still holds our token. See
     * {@code RedisAvailabilityCache} for why the check and the delete must be one step.
     */
    @Bean
    RedisScript<Long> availabilityLockReleaseScript() {
        return RedisScript.of(new ClassPathResource(AvailabilityConstants.RELEASE_LOCK_SCRIPT), Long.class);
    }
}
