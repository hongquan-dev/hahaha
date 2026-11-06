package com.fooddelivery.auth.web;

import com.fooddelivery.auth.config.properties.AuthProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Builds the {@code Set-Cookie} values of the two token cookies, so login, refresh and logout always set and clear
 * them with the same attributes.
 *
 * <ul>
 *   <li>{@code access_token}: {@code Path=/}, {@code SameSite=Lax}, lives as long as the access token</li>
 *   <li>{@code refresh_token}: {@code Path=/auth}, so it only travels to this service, {@code SameSite=Strict}, lives
 *       until the earlier of the token's idle expiry and its family's absolute expiry</li>
 * </ul>
 *
 * <p>Both are {@code HttpOnly}, so page scripts cannot read them, and {@code Secure} unless
 * {@code auth.cookie.secure=false}. No {@code Domain} is set, so they stay on the host that set them. Thread-safe.
 */
@Component
public class AuthCookies {

    // Shared with the code that reads the cookies (ApiRequest.getCookie), so read and written names cannot differ
    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    // A cookie is only replaced or cleared by one with the same name and path, so these never change between calls
    private static final String ACCESS_TOKEN_PATH = "/";
    private static final String REFRESH_TOKEN_PATH = "/auth";

    private final boolean secure;
    private final Duration accessTtl;
    private final Clock clock;

    public AuthCookies(AuthProperties properties, Clock clock) {
        this.secure = properties.cookie().secure();
        this.accessTtl = properties.jwt().accessTtl();
        this.clock = clock;
    }

    // Cookie holding a new access token, e.g. "access_token=eyJ...; Path=/; Max-Age=900; ...; SameSite=Lax"
    public ResponseCookie accessCookie(String accessToken) {
        return build(ACCESS_TOKEN_COOKIE, accessToken, ACCESS_TOKEN_PATH, accessTtl, "Lax");
    }

    /**
     * Cookie holding a new refresh token, kept by the browser until the earlier of the two expiries, so it never
     * outlives the token row.
     *
     * @param refreshToken    raw token, not the hash
     * @param expiresAt       idle expiry of the token ({@code RefreshToken.expiresAt})
     * @param familyExpiresAt absolute expiry of its family ({@code RefreshToken.familyExpiresAt})
     * @return the cookie; {@code Max-Age=0} if both times are already past
     */
    public ResponseCookie refreshCookie(String refreshToken, Instant expiresAt, Instant familyExpiresAt) {
        Instant end = expiresAt.isBefore(familyExpiresAt) ? expiresAt : familyExpiresAt;
        Duration maxAge = Duration.between(clock.instant(), end);
        return build(REFRESH_TOKEN_COOKIE, refreshToken, REFRESH_TOKEN_PATH,
                maxAge.isNegative() ? Duration.ZERO : maxAge, "Strict");
    }

    // Empty access_token with Max-Age=0, which makes the browser delete it
    public ResponseCookie clearAccessCookie() {
        return build(ACCESS_TOKEN_COOKIE, "", ACCESS_TOKEN_PATH, Duration.ZERO, "Lax");
    }

    // Empty refresh_token with Max-Age=0, which makes the browser delete it
    public ResponseCookie clearRefreshCookie() {
        return build(REFRESH_TOKEN_COOKIE, "", REFRESH_TOKEN_PATH, Duration.ZERO, "Strict");
    }

    // Response headers with one Set-Cookie line per cookie, for ResponseHandler.generateResponse(status, headers, body)
    public static HttpHeaders headersOf(ResponseCookie... cookies) {
        HttpHeaders headers = new HttpHeaders();
        for (ResponseCookie cookie : cookies) {
            headers.add(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return headers;
    }

    private ResponseCookie build(String name, String value, String path, Duration maxAge, String sameSite) {
        return ResponseCookie.from(name, value)
                .path(path)
                .maxAge(maxAge)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .build();
    }
}
