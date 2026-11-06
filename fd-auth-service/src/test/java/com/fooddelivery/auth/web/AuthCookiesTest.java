package com.fooddelivery.auth.web;

import com.fooddelivery.auth.config.properties.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link AuthCookies}, checking the {@code Set-Cookie} text the browser receives.
 */
class AuthCookiesTest {

    private static final Instant NOW = Instant.parse("2026-11-06T08:00:00Z");

    private final AuthCookies cookies = cookies(true);

    @Test
    void accessCookieHasAllAttributes() {
        String header = cookies.accessCookie("abc.def.ghi").toString();

        assertThat(header).startsWith("access_token=abc.def.ghi;")
                .contains("Path=/;", "Max-Age=900;", "HttpOnly", "Secure", "SameSite=Lax")
                .doesNotContain("Domain");
    }

    @Test
    void refreshCookieHasAllAttributes() {
        String header = cookies.refreshCookie("q8Zt3", NOW.plus(Duration.ofDays(7)), NOW.plus(Duration.ofDays(30)))
                .toString();

        assertThat(header).startsWith("refresh_token=q8Zt3;")
                .contains("Path=/auth;", "Max-Age=604800;", "HttpOnly", "Secure", "SameSite=Strict")
                .doesNotContain("Domain");
    }

    @Test
    void refreshCookieLivesUntilTheEarlierExpiry() {
        // Family has 2 days left, the token itself 7: the cookie must not outlive the family
        ResponseCookie cookie = cookies.refreshCookie("t", NOW.plus(Duration.ofDays(7)), NOW.plus(Duration.ofDays(2)));

        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void refreshCookieForPastExpiryHasZeroMaxAge() {
        ResponseCookie cookie = cookies.refreshCookie("t", NOW.minusSeconds(5), NOW.minusSeconds(1));

        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
    }

    @Test
    void clearCookiesUseTheSameNameAndPathWithZeroMaxAge() {
        ResponseCookie clearAccess = cookies.clearAccessCookie();
        ResponseCookie clearRefresh = cookies.clearRefreshCookie();

        assertThat(clearAccess.getName()).isEqualTo(AuthCookies.ACCESS_TOKEN_COOKIE);
        assertThat(clearAccess.getPath()).isEqualTo(cookies.accessCookie("x").getPath());
        assertThat(clearAccess.getValue()).isEmpty();
        assertThat(clearAccess.getMaxAge()).isEqualTo(Duration.ZERO);

        assertThat(clearRefresh.getName()).isEqualTo(AuthCookies.REFRESH_TOKEN_COOKIE);
        assertThat(clearRefresh.getPath()).isEqualTo(cookies.refreshCookie("x", NOW, NOW).getPath());
        assertThat(clearRefresh.getValue()).isEmpty();
        assertThat(clearRefresh.getMaxAge()).isEqualTo(Duration.ZERO);
    }

    @Test
    void secureCanBeTurnedOff() {
        assertThat(cookies(false).accessCookie("x").toString()).doesNotContain("Secure");
    }

    @Test
    void headersOfAddsOneSetCookiePerCookie() {
        HttpHeaders headers = AuthCookies.headersOf(cookies.clearAccessCookie(), cookies.clearRefreshCookie());

        assertThat(headers.get(HttpHeaders.SET_COOKIE)).hasSize(2);
    }

    private static AuthCookies cookies(boolean secure) {
        AuthProperties properties = new AuthProperties(
                new AuthProperties.Jwt("unused", "unused", "kid", "fd-auth-service", Duration.ofMinutes(15)),
                new AuthProperties.Refresh(Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(10)),
                new AuthProperties.Cookie(secure),
                List.of("http://localhost:5173"));
        return new AuthCookies(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
