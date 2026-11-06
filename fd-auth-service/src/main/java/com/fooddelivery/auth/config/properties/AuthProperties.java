package com.fooddelivery.auth.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Settings of login and tokens, bound at startup from the {@code auth.*} properties of
 * {@code application.properties}, where each property is described.
 *
 * <p>Checked when bound: a missing required value, a non-positive duration, an {@code idle-ttl} longer than
 * {@code absolute-ttl} or a malformed origin throws, so the application refuses to start. The RSA keys are only checked
 * for presence here; {@code JwtKeyConfig} parses them. Immutable.
 *
 * @param jwt            signing of the access tokens
 * @param refresh        lifetime of the refresh tokens
 * @param cookie         attributes of the token cookies
 * @param allowedOrigins origins allowed to send POST/PUT/PATCH/DELETE, e.g. {@code http://localhost:5173}
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(Jwt jwt, @DefaultValue Refresh refresh, @DefaultValue Cookie cookie,
                             List<String> allowedOrigins) {

    public AuthProperties {
        if (jwt == null) {
            throw new IllegalArgumentException("auth.jwt.* must be set");
        }
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("auth.allowed-origins must be set");
        }
        allowedOrigins = allowedOrigins.stream().map(String::trim).toList();
        allowedOrigins.forEach(AuthProperties::checkOrigin);
    }

    /**
     * Signing of the access tokens ({@code auth.jwt.*}).
     *
     * @param privateKey Base64 body of the PKCS#8 PEM private key, parsed by {@code JwtKeyConfig}
     * @param publicKey  Base64 body of the X.509 PEM public key, parsed by {@code JwtKeyConfig}
     * @param keyId      {@code kid} of the token header and the JWKS, e.g. {@code fd-auth-dev-1}
     * @param issuer     {@code iss} claim of every token, e.g. {@code fd-auth-service}
     * @param accessTtl  lifetime of an access token and of its cookie
     */
    public record Jwt(String privateKey, String publicKey, String keyId, String issuer,
                      @DefaultValue("15m") Duration accessTtl) {

        public Jwt {
            requireText(privateKey, "auth.jwt.private-key");
            requireText(publicKey, "auth.jwt.public-key");
            requireText(keyId, "auth.jwt.key-id");
            requireText(issuer, "auth.jwt.issuer");
            requirePositive(accessTtl, "auth.jwt.access-ttl");
        }
    }

    /**
     * Lifetime of the refresh tokens ({@code auth.refresh.*}).
     *
     * @param idleTtl     lifetime of each refresh token; every refresh issues a new one
     * @param absoluteTtl maximum lifetime of a session, counted from login, never shorter than {@code idleTtl}
     * @param reuseGrace  time after a rotation during which reusing the old token is a race between tabs, not a theft
     */
    public record Refresh(@DefaultValue("7d") Duration idleTtl, @DefaultValue("30d") Duration absoluteTtl,
                          @DefaultValue("10s") Duration reuseGrace) {

        public Refresh {
            requirePositive(idleTtl, "auth.refresh.idle-ttl");
            requirePositive(absoluteTtl, "auth.refresh.absolute-ttl");
            requirePositive(reuseGrace, "auth.refresh.reuse-grace");
            if (idleTtl.compareTo(absoluteTtl) > 0) {
                throw new IllegalArgumentException("auth.refresh.idle-ttl (" + idleTtl
                        + ") must not be longer than auth.refresh.absolute-ttl (" + absoluteTtl + ")");
            }
        }
    }

    /**
     * Attributes of the token cookies ({@code auth.cookie.*}).
     *
     * @param secure {@code Secure} attribute: the browser only sends the cookies over HTTPS or to localhost
     */
    public record Cookie(@DefaultValue("true") boolean secure) {
    }

    // An Origin header is exactly scheme://host[:port], so anything else (trailing slash, path, "*") would never match
    private static void checkOrigin(String origin) {
        String error = "auth.allowed-origins: '" + origin
                + "' must be scheme://host[:port], e.g. http://localhost:5173";
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(error);
        }
        boolean httpScheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        boolean noPath = uri.getRawPath() == null || uri.getRawPath().isEmpty();
        if (!httpScheme || uri.getHost() == null || !noPath || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(error);
        }
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must be set");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be a positive duration, e.g. 15m");
        }
    }
}
