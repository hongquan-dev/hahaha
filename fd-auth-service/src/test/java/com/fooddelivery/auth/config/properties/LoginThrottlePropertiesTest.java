package com.fooddelivery.auth.config.properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of {@link LoginThrottleProperties}: the checks of the compact constructor, called directly, and the
 * defaults, bound from a map of {@code auth.login.*} properties by Spring Boot's {@link Binder} as at startup.
 */
class LoginThrottlePropertiesTest {

    private static final Duration POSITIVE = Duration.ofMinutes(1);

    @Test
    void defaultsApplyWhenNothingIsBound() {
        LoginThrottleProperties properties = bind(Map.of());

        assertThat(properties.maxFailures()).isEqualTo(10);
        assertThat(properties.failureWindow()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.lockDuration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void boundValuesOverrideTheDefaults() {
        LoginThrottleProperties properties = bind(Map.of("auth.login.max-failures", "3",
                "auth.login.failure-window", "1h", "auth.login.lock-duration", "30s"));

        assertThat(properties.maxFailures()).isEqualTo(3);
        assertThat(properties.failureWindow()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.lockDuration()).isEqualTo(Duration.ofSeconds(30));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void maxFailuresBelowOneIsRejected(int maxFailures) {
        assertThatThrownBy(() -> new LoginThrottleProperties(maxFailures, POSITIVE, POSITIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth.login.max-failures");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void nonPositiveFailureWindowIsRejected(Long seconds) {
        Duration window = seconds == null ? null : Duration.ofSeconds(seconds);

        assertThatThrownBy(() -> new LoginThrottleProperties(10, window, POSITIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("auth.login.failure-window must be a positive duration");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void nonPositiveLockDurationIsRejected(Long seconds) {
        Duration lock = seconds == null ? null : Duration.ofSeconds(seconds);

        assertThatThrownBy(() -> new LoginThrottleProperties(10, POSITIVE, lock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("auth.login.lock-duration must be a positive duration");
    }

    // Binds as Spring Boot does at startup for @ConfigurationProperties(prefix = "auth.login")
    private static LoginThrottleProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("auth.login", LoginThrottleProperties.class);
    }
}
