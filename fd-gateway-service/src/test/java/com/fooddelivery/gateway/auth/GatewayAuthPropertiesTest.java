package com.fooddelivery.gateway.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of {@link GatewayAuthProperties}: the checks of the compact constructor, called directly, and the
 * defaults, bound from a map of {@code gateway.auth.*} properties by Spring Boot's {@link Binder} as at startup,
 * without an application context.
 */
class GatewayAuthPropertiesTest {

    private static final URI JWKS_URI = URI.create("http://localhost:8081/auth/.well-known/jwks.json");
    private static final String ISSUER = "fd-auth-service";
    private static final Duration POSITIVE = Duration.ofSeconds(1);

    @Test
    void keepsEveryValue() {
        GatewayAuthProperties properties = new GatewayAuthProperties(JWKS_URI, ISSUER, Duration.ofMinutes(1),
                Duration.ofSeconds(10), Duration.ofMillis(500), List.of("/order/health"));

        assertThat(properties.jwksUri()).isEqualTo(JWKS_URI);
        assertThat(properties.issuer()).isEqualTo(ISSUER);
        assertThat(properties.jwksCacheTtl()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.refetchInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.jwksTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.publicPaths()).containsExactly("/order/health");
    }

    @Test
    void missingJwksUriIsRejected() {
        assertThatThrownBy(() -> new GatewayAuthProperties(null, ISSUER, POSITIVE, POSITIVE, POSITIVE, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gateway.auth.jwks-uri");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t"})
    void missingOrBlankIssuerIsRejected(String issuer) {
        assertThatThrownBy(() -> new GatewayAuthProperties(JWKS_URI, issuer, POSITIVE, POSITIVE, POSITIVE, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gateway.auth.issuer");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1000, -1, 0})
    void nonPositiveJwksCacheTtlIsRejected(long millis) {
        assertRejected(d -> properties(d, POSITIVE, POSITIVE), millis, "gateway.auth.jwks-cache-ttl");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1000, -1, 0})
    void nonPositiveRefetchIntervalIsRejected(long millis) {
        assertRejected(d -> properties(POSITIVE, d, POSITIVE), millis, "gateway.auth.refetch-interval");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1000, -1, 0})
    void nonPositiveJwksTimeoutIsRejected(long millis) {
        assertRejected(d -> properties(POSITIVE, POSITIVE, d), millis, "gateway.auth.jwks-timeout");
    }

    @Test
    void smallestPositiveDurationsAreAccepted() {
        Duration smallest = Duration.ofNanos(1);

        GatewayAuthProperties properties = properties(smallest, smallest, smallest);

        assertThat(properties.jwksCacheTtl()).isEqualTo(smallest);
        assertThat(properties.refetchInterval()).isEqualTo(smallest);
        assertThat(properties.jwksTimeout()).isEqualTo(smallest);
    }

    @Test
    void defaultsApplyWhenOnlyTheRequiredPropertiesAreBound() {
        GatewayAuthProperties properties = bind(Map.of("gateway.auth.jwks-uri", JWKS_URI.toString(),
                "gateway.auth.issuer", ISSUER));

        assertThat(properties.jwksUri()).isEqualTo(JWKS_URI);
        assertThat(properties.issuer()).isEqualTo(ISSUER);
        assertThat(properties.jwksCacheTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.refetchInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.jwksTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.publicPaths()).isEmpty();
    }

    @Test
    void boundValuesOverrideTheDefaults() {
        GatewayAuthProperties properties = bind(Map.of("gateway.auth.jwks-uri", JWKS_URI.toString(),
                "gateway.auth.issuer", ISSUER,
                "gateway.auth.jwks-cache-ttl", "1m",
                "gateway.auth.refetch-interval", "10s",
                "gateway.auth.jwks-timeout", "500ms",
                "gateway.auth.public-paths", "/order/health,/auth/**"));

        assertThat(properties.jwksCacheTtl()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.refetchInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.jwksTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.publicPaths()).containsExactly("/order/health", "/auth/**");
    }

    @Test
    void bindingWithoutIssuerFailsSoTheApplicationRefusesToStart() {
        assertThatThrownBy(() -> bind(Map.of("gateway.auth.jwks-uri", JWKS_URI.toString())))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("gateway.auth.issuer");
    }

    private static GatewayAuthProperties properties(Duration cacheTtl, Duration refetchInterval, Duration timeout) {
        return new GatewayAuthProperties(JWKS_URI, ISSUER, cacheTtl, refetchInterval, timeout, List.of());
    }

    private static void assertRejected(Function<Duration, GatewayAuthProperties> create, long millis, String name) {
        assertThatThrownBy(() -> create.apply(Duration.ofMillis(millis)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(name);
    }

    // Binds as Spring Boot does at startup for @ConfigurationProperties(prefix = "gateway.auth")
    private static GatewayAuthProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("gateway.auth", GatewayAuthProperties.class);
    }
}
