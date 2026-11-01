package com.keyloop.scheduler.api.config.appointment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Beans of the booking write path. Today it only binds {@link BookingProperties}; the
 * transaction runner ({@code TransactionOperations}) comes from Spring Boot. Spec07 §9.
 *
 * <pre>
 * application.yml  scheduler.booking.max-pairs ──► BookingProperties ──► CandidateSelectorImpl
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookingProperties.class)
public class AppointmentConfig {
}
