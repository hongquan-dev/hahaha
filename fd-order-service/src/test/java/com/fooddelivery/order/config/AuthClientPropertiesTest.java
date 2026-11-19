package com.fooddelivery.order.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of {@link AuthClientProperties}: the check of the compact constructor, called directly, and the default
 * timeout, bound from a map of {@code services.auth.*} properties by Spring Boot's {@link Binder} as at startup,
 * without an application context.
 */
class AuthClientPropertiesTest {

    @Test
    void keepsTheTimeout() {
        assertThat(new AuthClientProperties(Duration.ofMillis(500)).timeout()).isEqualTo(Duration.ofMillis(500));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1000, -1, 0})
    void nonPositiveTimeoutIsRejected(long millis) {
        assertThatThrownBy(() -> new AuthClientProperties(Duration.ofMillis(millis)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("services.auth.timeout");
    }

    @Test
    void smallestPositiveTimeoutIsAccepted() {
        assertThat(new AuthClientProperties(Duration.ofNanos(1)).timeout()).isEqualTo(Duration.ofNanos(1));
    }

    @Test
    void timeoutDefaultsToTwoSecondsWhenNothingIsBound() {
        assertThat(bind(Map.of()).timeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void boundTimeoutOverridesTheDefault() {
        assertThat(bind(Map.of("services.auth.timeout", "500ms")).timeout()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void bindingANonPositiveTimeoutFailsSoTheApplicationRefusesToStart() {
        assertThatThrownBy(() -> bind(Map.of("services.auth.timeout", "0s")))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("services.auth.timeout");
    }

    // Binds as Spring Boot does at startup for @ConfigurationProperties(prefix = "services.auth")
    private static AuthClientProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("services.auth", AuthClientProperties.class);
    }
}
