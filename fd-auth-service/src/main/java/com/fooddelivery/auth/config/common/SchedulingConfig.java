package com.fooddelivery.auth.config.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler at startup, so {@code @Scheduled} methods such as
 * {@code DataCleanupService.scheduledCleanUp} run. They share one scheduler thread, enough for jobs that run hourly.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
