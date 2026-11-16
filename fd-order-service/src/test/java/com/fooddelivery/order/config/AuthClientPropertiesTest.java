package com.fooddelivery.order.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of {@link AuthClientProperties}: the checks of the compact constructor, called directly, and the default
 * timeout, bound from a map of {@code services.auth.*} properties by Spring Boot's {@link Binder} as at startup,
 * without an application context.
 */
class AuthClientPropertiesTest {

    private static final URI URL = URI.create("http://localhost:8081");

    @Test
    void keepsTheUrlAndTimeout() {
        AuthClientProperties properties = new AuthClientProperties(URL, Duration.ofMillis(500));

        assertThat(properties.httpUrl()).isEqualTo(URL);
        assertThat(properties.timeout()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void missingUrlIsRejected() {
        assertThatThrownBy(() -> new AuthClientProperties(null, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("services.auth.http-url");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1000, -1, 0})
    void nonPositiveTimeoutIsRejected(long millis) {
        assertThatThrownBy(() -> new AuthClientProperties(URL, Duration.ofMillis(millis)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("services.auth.timeout");
    }

    @Test
    void smallestPositiveTimeoutIsAccepted() {
        assertThat(new AuthClientProperties(URL, Duration.ofNanos(1)).timeout()).isEqualTo(Duration.ofNanos(1));
    }

    @Test
    void timeoutDefaultsToTwoSecondsWhenBound() {
        AuthClientProperties properties = bind(Map.of("services.auth.http-url", "http://localhost:8081"));

        assertThat(properties.httpUrl()).isEqualTo(URL);
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void boundTimeoutOverridesTheDefault() {
        AuthClientProperties properties = bind(Map.of("services.auth.http-url", "http://localhost:8081",
                "services.auth.timeout", "500ms"));

        assertThat(properties.timeout()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void bindingWithoutUrlFailsSoTheApplicationRefusesToStart() {
        assertThatThrownBy(() -> bind(Map.of("services.auth.timeout", "1s")))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("services.auth.http-url");
    }

    // Binds as Spring Boot does at startup for @ConfigurationProperties(prefix = "services.auth")
    private static AuthClientProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("services.auth", AuthClientProperties.class);
    }
}
