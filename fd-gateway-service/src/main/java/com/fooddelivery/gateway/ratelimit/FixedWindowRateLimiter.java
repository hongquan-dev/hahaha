package com.fooddelivery.gateway.ratelimit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts requests per key in fixed windows, e.g. at most 30 per minute for one client IP and path, at request time for
 * the {@code RateLimit} filter.
 *
 * <p>A key's window starts with its first request and lasts {@code gateway.rate-limit.window}; the request after the
 * limit is rejected until the window ends. Counts live in this gateway's memory only: they are lost on restart, and
 * several gateway instances each count on their own. Windows that have ended are swept once more than
 * {@link #DEFAULT_SWEEP_THRESHOLD} keys are held, so requests from many different IPs cannot grow the map without
 * bound. Thread-safe: each key is updated atomically.
 */
@Component
public class FixedWindowRateLimiter {

    // Keys held before ended windows are swept; one entry is about a hundred bytes, so this stays around a megabyte
    static final int DEFAULT_SWEEP_THRESHOLD = 10_000;

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final int sweepThreshold;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public FixedWindowRateLimiter(RateLimitProperties properties, Clock clock) {
        this(properties, clock, DEFAULT_SWEEP_THRESHOLD);
    }

    // Package-private: lets tests reach the sweep with a few keys
    FixedWindowRateLimiter(RateLimitProperties properties, Clock clock, int sweepThreshold) {
        this.limit = properties.limit();
        this.window = properties.window();
        this.clock = clock;
        this.sweepThreshold = sweepThreshold;
    }

    /**
     * Counts one request for a key.
     *
     * @param key what is limited, e.g. {@code "POST /auth/login 203.0.113.7"}
     * @return allowed while the key is within the limit of its current window, otherwise rejected with the time left
     *         in that window
     */
    public RateLimitDecision tryAcquire(String key) {
        Instant now = clock.instant();
        Window current = windows.compute(key, (k, existing) -> existing == null || existing.hasEnded(now, window)
                ? new Window(now, 1) : new Window(existing.start(), existing.count() + 1));
        if (windows.size() > sweepThreshold) {
            windows.entrySet().removeIf(entry -> entry.getValue().hasEnded(now, window));
        }
        return current.count() <= limit
                ? RateLimitDecision.allow() : RateLimitDecision.reject(Duration.between(now, current.end(window)));
    }

    // Number of keys held, for tests of the sweep
    int size() {
        return windows.size();
    }

    // count: requests seen in the window so far, including rejected ones
    private record Window(Instant start, int count) {

        Instant end(Duration length) {
            return start.plus(length);
        }

        boolean hasEnded(Instant now, Duration length) {
            return !now.isBefore(end(length));
        }
    }
}
