package com.fooddelivery.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Settings of the access token check at the gateway, bound at startup from the {@code gateway.auth.*} properties of
 * {@code application.properties}, where each property is described.
 *
 * <p>Checked when bound: a missing JWKS URI or issuer, or a non-positive duration, throws, so the application refuses
 * to start. Immutable.
 *
 * @param jwksUri          JWK Set of auth-service, e.g. {@code http://localhost:8081/auth/.well-known/jwks.json}
 * @param issuer           expected {@code iss} claim, the same as {@code auth.jwt.issuer} of auth-service
 * @param jwksCacheTtl     how long fetched keys are reused before fetching again (default 5m)
 * @param refetchInterval  minimum time between two fetches triggered by an unknown {@code kid} (default 30s)
 * @param jwksTimeout      maximum wait for auth-service when fetching the keys (default 2s)
 * @param publicPaths      paths of protected routes that need no token, as Spring path patterns, e.g.
 *                         {@code /order/health}; empty by default
 */
@ConfigurationProperties(prefix = "gateway.auth")
public record GatewayAuthProperties(URI jwksUri, String issuer,
                                    @DefaultValue("5m") Duration jwksCacheTtl,
                                    @DefaultValue("30s") Duration refetchInterval,
                                    @DefaultValue("2s") Duration jwksTimeout,
                                    @DefaultValue List<String> publicPaths) {

    public GatewayAuthProperties {
        if (jwksUri == null) {
            throw new IllegalArgumentException("gateway.auth.jwks-uri must be set");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("gateway.auth.issuer must be set");
        }
        requirePositive(jwksCacheTtl, "jwks-cache-ttl");
        requirePositive(refetchInterval, "refetch-interval");
        requirePositive(jwksTimeout, "jwks-timeout");
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("gateway.auth." + name + " must be positive, got " + value);
        }
    }
}
