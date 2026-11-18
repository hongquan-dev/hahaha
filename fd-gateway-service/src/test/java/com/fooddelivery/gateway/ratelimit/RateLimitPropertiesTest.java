package com.fooddelivery.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of {@link RateLimitProperties}: the checks of the compact constructor, called directly, and the defaults,
 * bound from a map of {@code gateway.rate-limit.*} properties by Spring Boot's {@link Binder} as at startup.
 */
class RateLimitPropertiesTest {

    @Test
    void defaultsApplyWhenNothingIsBound() {
        RateLimitProperties properties = bind(Map.of());

        assertThat(properties.limit()).isEqualTo(30);
        assertThat(properties.window()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.trustedProxyHops()).isZero();
    }

    @Test
    void boundValuesOverrideTheDefaults() {
        RateLimitProperties properties = bind(Map.of("gateway.rate-limit.limit", "5",
                "gateway.rate-limit.window", "10s",
                "gateway.rate-limit.trusted-proxy-hops", "1"));

        assertThat(properties.limit()).isEqualTo(5);
        assertThat(properties.window()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.trustedProxyHops()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void limitBelowOneIsRejected(int limit) {
        assertThatThrownBy(() -> new RateLimitProperties(limit, Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gateway.rate-limit.limit");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1000})
    void nonPositiveWindowIsRejected(long millis) {
        assertThatThrownBy(() -> new RateLimitProperties(30, Duration.ofMillis(millis), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gateway.rate-limit.window");
    }

    @Test
    void negativeProxyHopsAreRejected() {
        assertThatThrownBy(() -> new RateLimitProperties(30, Duration.ofMinutes(1), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gateway.rate-limit.trusted-proxy-hops");
    }

    // Binds as Spring Boot does at startup for @ConfigurationProperties(prefix = "gateway.rate-limit")
    private static RateLimitProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("gateway.rate-limit", RateLimitProperties.class);
    }
}
