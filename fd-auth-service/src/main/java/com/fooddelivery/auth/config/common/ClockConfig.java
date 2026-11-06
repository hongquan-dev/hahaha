package com.fooddelivery.auth.config.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Source of the current time for token issuing and expiry checks. Injected instead of calling {@code Instant.now()}
 * directly, so tests can pass a fixed clock and move it forward instead of waiting.
 */
@Configuration
public class ClockConfig {

    // System clock in UTC; token times are Instants, so the zone only affects code that formats them
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
