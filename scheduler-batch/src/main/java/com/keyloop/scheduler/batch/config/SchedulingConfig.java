package com.keyloop.scheduler.batch.config;

import javax.sql.DataSource;

import com.keyloop.scheduler.batch.constant.SchedulingConstants;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} and guards every scheduled method with a ShedLock row,
 * so a job runs on only one instance when scheduler-batch is scaled out (Spec01 BAT-07).
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = SchedulingConstants.DEFAULT_LOCK_AT_MOST_FOR)
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                // Lock timestamps come from Postgres, not from each container's clock
                .usingDbTime()
                .build());
    }
}
