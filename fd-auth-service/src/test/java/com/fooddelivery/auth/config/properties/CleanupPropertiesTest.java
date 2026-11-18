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
 * Unit tests of {@link CleanupProperties}: the checks of the compact constructor, called directly, and the defaults,
 * bound from a map of {@code auth.cleanup.*} properties by Spring Boot's {@link Binder} as at startup.
 */
class CleanupPropertiesTest {

    @Test
    void defaultsApplyWhenNothingIsBound() {
        CleanupProperties properties = bind(Map.of());

        assertThat(properties.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.batchSize()).isEqualTo(500);
    }

    @Test
    void boundValuesOverrideTheDefaults() {
        CleanupProperties properties = bind(Map.of("auth.cleanup.retention", "1d", "auth.cleanup.batch-size", "50"));

        assertThat(properties.retention()).isEqualTo(Duration.ofDays(1));
        assertThat(properties.batchSize()).isEqualTo(50);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void nonPositiveRetentionIsRejected(Long hours) {
        Duration retention = hours == null ? null : Duration.ofHours(hours);

        assertThatThrownBy(() -> new CleanupProperties(retention, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("auth.cleanup.retention must be a positive duration");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void batchSizeBelowOneIsRejected(int batchSize) {
        assertThatThrownBy(() -> new CleanupProperties(Duration.ofDays(7), batchSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth.cleanup.batch-size");
    }

    // Binds as Spring Boot does at startup for @ConfigurationProperties(prefix = "auth.cleanup")
    private static CleanupProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("auth.cleanup", CleanupProperties.class);
    }
}
