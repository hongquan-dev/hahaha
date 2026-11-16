package com.fooddelivery.auth.config.properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of the checks in the compact constructors of {@link AuthProperties} and its nested records, which make
 * the application refuse to start on a bad {@code auth.*} value. The defaults are checked by binding a map of
 * properties with Spring Boot's {@link Binder}, without an application context.
 */
class AuthPropertiesTest {

    private static final AuthProperties.Jwt JWT = jwt("private", "public", "kid", "fd-auth-service");
    private static final AuthProperties.Refresh REFRESH =
            new AuthProperties.Refresh(Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(10));
    private static final AuthProperties.Cookie COOKIE = new AuthProperties.Cookie(true);

    @Test
    void omittedOptionalPropertiesTakeTheirDefaults() {
        Map<String, String> source = Map.of(
                "auth.jwt.private-key", "private",
                "auth.jwt.public-key", "public",
                "auth.jwt.key-id", "kid",
                "auth.jwt.issuer", "fd-auth-service",
                "auth.allowed-origins", "http://localhost:5173");

        AuthProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("auth", AuthProperties.class).get();

        assertThat(properties.jwt().accessTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.refresh().idleTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.refresh().absoluteTtl()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.refresh().reuseGrace()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.cookie().secure()).isTrue();
        assertThat(properties.allowedOrigins()).containsExactly("http://localhost:5173");
    }

    @Test
    void allowedOriginsAreTrimmed() {
        AuthProperties properties = new AuthProperties(JWT, REFRESH, COOKIE,
                List.of(" http://localhost:5173 ", "https://app.example.com\t"));

        assertThat(properties.allowedOrigins()).containsExactly("http://localhost:5173", "https://app.example.com");
    }

    @Test
    void missingJwtSectionIsRejected() {
        assertThatThrownBy(() -> new AuthProperties(null, REFRESH, COOKIE, List.of("http://localhost:5173")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("auth.jwt.* must be set");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingAllowedOriginsAreRejected(List<String> allowedOrigins) {
        assertThatThrownBy(() -> new AuthProperties(JWT, REFRESH, COOKIE, allowedOrigins))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("auth.allowed-origins must be set");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "https://app.example.com", "http://127.0.0.1:8080",
            "https://example.com:443"})
    void schemeHostAndOptionalPortIsAValidOrigin(String origin) {
        assertThatCode(() -> new AuthProperties(JWT, REFRESH, COOKIE, List.of(origin))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173/", "http://localhost:5173/app", "*", "localhost:5173",
            "ftp://example.com", "http://example.com?x=1", "http://example.com#top", "http://user@example.com",
            "http://exa mple.com", "http://", ""})
    void originThatIsNotSchemeHostAndPortIsRejected(String origin) {
        assertThatThrownBy(() -> new AuthProperties(JWT, REFRESH, COOKIE, List.of("http://localhost:5173", origin)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("auth.allowed-origins: '" + origin + "'");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void blankPrivateKeyIsRejected(String value) {
        assertRequired(() -> jwt(value, "public", "kid", "iss"), "auth.jwt.private-key");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void blankPublicKeyIsRejected(String value) {
        assertRequired(() -> jwt("private", value, "kid", "iss"), "auth.jwt.public-key");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void blankKeyIdIsRejected(String value) {
        assertRequired(() -> jwt("private", "public", value, "iss"), "auth.jwt.key-id");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void blankIssuerIsRejected(String value) {
        assertRequired(() -> jwt("private", "public", "kid", value), "auth.jwt.issuer");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void nonPositiveAccessTtlIsRejected(Long seconds) {
        Duration ttl = seconds == null ? null : Duration.ofSeconds(seconds);

        assertNotPositive(() -> new AuthProperties.Jwt("private", "public", "kid", "iss", ttl),
                "auth.jwt.access-ttl");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void nonPositiveIdleTtlIsRejected(Long seconds) {
        Duration ttl = seconds == null ? null : Duration.ofSeconds(seconds);

        assertNotPositive(() -> new AuthProperties.Refresh(ttl, Duration.ofDays(30), Duration.ofSeconds(10)),
                "auth.refresh.idle-ttl");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void nonPositiveAbsoluteTtlIsRejected(Long seconds) {
        Duration ttl = seconds == null ? null : Duration.ofSeconds(seconds);

        assertNotPositive(() -> new AuthProperties.Refresh(Duration.ofDays(7), ttl, Duration.ofSeconds(10)),
                "auth.refresh.absolute-ttl");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void nonPositiveReuseGraceIsRejected(Long seconds) {
        Duration grace = seconds == null ? null : Duration.ofSeconds(seconds);

        assertNotPositive(() -> new AuthProperties.Refresh(Duration.ofDays(7), Duration.ofDays(30), grace),
                "auth.refresh.reuse-grace");
    }

    @Test
    void idleTtlLongerThanAbsoluteTtlIsRejected() {
        assertThatThrownBy(() -> new AuthProperties.Refresh(Duration.ofDays(31), Duration.ofDays(30),
                Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("auth.refresh.idle-ttl")
                .hasMessageContaining("auth.refresh.absolute-ttl");
    }

    @Test
    void idleTtlEqualToAbsoluteTtlIsAccepted() {
        assertThatCode(() -> new AuthProperties.Refresh(Duration.ofDays(30), Duration.ofDays(30),
                Duration.ofSeconds(10))).doesNotThrowAnyException();
    }

    private static AuthProperties.Jwt jwt(String privateKey, String publicKey, String keyId, String issuer) {
        return new AuthProperties.Jwt(privateKey, publicKey, keyId, issuer, Duration.ofMinutes(15));
    }

    private static void assertRequired(Runnable construction, String property) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(property + " must be set");
    }

    private static void assertNotPositive(Runnable construction, String property) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(property + " must be a positive duration");
    }
}
