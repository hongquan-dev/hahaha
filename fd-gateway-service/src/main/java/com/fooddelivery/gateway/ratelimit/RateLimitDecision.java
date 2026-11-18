package com.fooddelivery.gateway.ratelimit;

import java.time.Duration;

/**
 * Answer of {@link FixedWindowRateLimiter#tryAcquire} for one request.
 *
 * @param allowed    whether the request is within the limit
 * @param retryAfter time until the window ends and requests are allowed again; {@link Duration#ZERO} when allowed
 */
public record RateLimitDecision(boolean allowed, Duration retryAfter) {

    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, Duration.ZERO);
    }

    public static RateLimitDecision reject(Duration retryAfter) {
        return new RateLimitDecision(false, retryAfter);
    }
}
