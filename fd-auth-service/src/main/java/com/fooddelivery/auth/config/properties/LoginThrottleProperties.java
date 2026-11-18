package com.fooddelivery.auth.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings of the lockout after failed logins, bound at startup from the {@code auth.login.*} properties of
 * {@code application.properties}, where each property is described. Read by {@code LoginThrottleService}.
 *
 * <p>Checked when bound: fewer than 1 failure or a non-positive duration throws, so the application refuses to start.
 * Immutable.
 *
 * @param maxFailures   failed logins within {@code failureWindow} that lock the identity (default 10)
 * @param failureWindow time over which failures are counted, from the first one (default 15m)
 * @param lockDuration  how long a locked identity is refused, even with the right password (default 5m)
 */
@ConfigurationProperties(prefix = "auth.login")
public record LoginThrottleProperties(@DefaultValue("10") int maxFailures,
                                      @DefaultValue("15m") Duration failureWindow,
                                      @DefaultValue("5m") Duration lockDuration) {

    public LoginThrottleProperties {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("auth.login.max-failures must be at least 1, got " + maxFailures);
        }
        requirePositive(failureWindow, "auth.login.failure-window");
        requirePositive(lockDuration, "auth.login.lock-duration");
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be a positive duration, e.g. 15m");
        }
    }
}
