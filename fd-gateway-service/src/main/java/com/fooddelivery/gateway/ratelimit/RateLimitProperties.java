package com.fooddelivery.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings of the {@code RateLimit} filter, bound at startup from the {@code gateway.rate-limit.*} properties of
 * {@code application.properties}, where each property is described.
 *
 * <p>Checked when bound: a limit below 1, a non-positive window or a negative number of hops throws, so the
 * application refuses to start. Immutable.
 *
 * @param limit             requests one client IP may send to one path within a window (default 30)
 * @param window            length of a counting window (default 1m)
 * @param trustedProxyHops  proxies in front of the gateway whose {@code X-Forwarded-For} gives the client IP; 0 (the
 *                          default) uses the IP of the direct connection
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(@DefaultValue("30") int limit,
                                  @DefaultValue("1m") Duration window,
                                  @DefaultValue("0") int trustedProxyHops) {

    public RateLimitProperties {
        if (limit < 1) {
            throw new IllegalArgumentException("gateway.rate-limit.limit must be at least 1, got " + limit);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("gateway.rate-limit.window must be positive, got " + window);
        }
        if (trustedProxyHops < 0) {
            throw new IllegalArgumentException(
                    "gateway.rate-limit.trusted-proxy-hops must not be negative, got " + trustedProxyHops);
        }
    }
}
