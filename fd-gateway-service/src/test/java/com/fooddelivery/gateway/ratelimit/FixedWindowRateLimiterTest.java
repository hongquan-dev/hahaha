package com.fooddelivery.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link FixedWindowRateLimiter} with a limit of 3 per minute and a clock the tests move forward instead
 * of waiting.
 */
class FixedWindowRateLimiterTest {

    private static final RateLimitProperties PROPERTIES = new RateLimitProperties(3, Duration.ofMinutes(1), 0);
    private static final String KEY = "POST /auth/login 203.0.113.7";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T08:00:00Z"));
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(PROPERTIES, clock);

    @Test
    void requestsUpToTheLimitAreAllowed() {
        IntStream.range(0, 3).forEach(i -> assertThat(limiter.tryAcquire(KEY).allowed()).as("request " + i).isTrue());
    }

    @Test
    void requestAfterTheLimitIsRejectedUntilTheWindowEnds() {
        exhaust(KEY);
        clock.advance(Duration.ofSeconds(20));

        RateLimitDecision decision = limiter.tryAcquire(KEY);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfter()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void newWindowStartsWhenTheOldOneEnds() {
        exhaust(KEY);
        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire(KEY).allowed()).isTrue();
    }

    @Test
    void rejectedRequestsDoNotExtendTheWindow() {
        exhaust(KEY);
        clock.advance(Duration.ofSeconds(59));
        limiter.tryAcquire(KEY);
        clock.advance(Duration.ofSeconds(1));

        assertThat(limiter.tryAcquire(KEY).allowed()).isTrue();
    }

    @Test
    void keysAreCountedSeparately() {
        exhaust(KEY);

        assertThat(limiter.tryAcquire("POST /auth/login 198.51.100.9").allowed()).isTrue();
        assertThat(limiter.tryAcquire("POST /auth/register 203.0.113.7").allowed()).isTrue();
    }

    @Test
    void endedWindowsAreSweptOnceTheThresholdIsPassed() {
        FixedWindowRateLimiter small = new FixedWindowRateLimiter(PROPERTIES, clock, 2);
        small.tryAcquire("a");
        small.tryAcquire("b");
        clock.advance(Duration.ofMinutes(1));

        small.tryAcquire("c");

        assertThat(small.size()).isEqualTo(1);
    }

    private void exhaust(String key) {
        IntStream.range(0, 3).forEach(i -> limiter.tryAcquire(key));
    }

    // Clock the test moves forward instead of waiting
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
