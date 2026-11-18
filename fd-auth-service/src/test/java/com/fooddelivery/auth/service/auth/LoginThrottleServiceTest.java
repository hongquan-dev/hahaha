package com.fooddelivery.auth.service.auth;

import com.fooddelivery.auth.common.model.LoginFailure;
import com.fooddelivery.auth.config.properties.LoginThrottleProperties;
import com.fooddelivery.auth.repository.LoginFailureRepository;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.web.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link LoginThrottleService} with 3 failures per 15 minutes locking for 5 minutes. The mocked
 * repository keeps the {@code login_failures} rows in a map, and the clock is moved forward instead of waiting.
 */
class LoginThrottleServiceTest {

    private static final LoginThrottleProperties PROPERTIES =
            new LoginThrottleProperties(3, Duration.ofMinutes(15), Duration.ofMinutes(5));
    private static final String KEY = "a".repeat(64);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T08:00:00Z"));
    private final Map<String, LoginFailure> rows = new HashMap<>();
    private final LoginFailureRepository repository = mock(LoginFailureRepository.class);
    private final LoginThrottleService throttle = new LoginThrottleService(repository, PROPERTIES, clock);

    @BeforeEach
    void tableInAMap() {
        doAnswer(invocation -> {
            rows.computeIfAbsent(invocation.getArgument(0), key -> {
                LoginFailure failure = new LoginFailure();
                failure.setWindowStartedAt(invocation.getArgument(1));
                return failure;
            });
            return null;
        }).when(repository).insertIfAbsent(anyString(), any(Instant.class));
        when(repository.findByIdForUpdate(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.<String>getArgument(0))));
        when(repository.findById(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.<String>getArgument(0))));
        doAnswer(invocation -> rows.remove(invocation.<String>getArgument(0))).when(repository).deleteById(anyString());
    }

    @Test
    void failuresBelowTheMaximumDoNotLock() {
        fail(2);

        assertNotLocked(KEY);
    }

    @Test
    void failureReachingTheMaximumLocksWith429() {
        fail(3);

        BusinessException e = catchThrowableOfType(BusinessException.class, () -> throttle.checkNotLocked(KEY));
        assertThat(e).isNotNull();
        assertThat(e.getStatus()).isEqualTo(ApiStatus.TOO_MANY_REQUESTS);
        assertThat(e.getCode()).isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
    }

    @Test
    void lockEndsAfterTheLockDuration() {
        fail(3);
        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertLocked(KEY);

        clock.advance(Duration.ofSeconds(1));
        assertNotLocked(KEY);
    }

    @Test
    void countStartsOverAfterALock() {
        fail(3);
        clock.advance(Duration.ofMinutes(5));

        fail(2);

        assertNotLocked(KEY);
    }

    @Test
    void failuresOfAnEndedWindowAreForgotten() {
        fail(2);
        clock.advance(Duration.ofMinutes(15));

        fail(2);

        assertNotLocked(KEY);
    }

    @Test
    void resetForgetsTheFailures() {
        fail(2);
        throttle.reset(KEY);

        fail(2);

        assertNotLocked(KEY);
    }

    @Test
    void otherKeysAreNotLocked() {
        fail(3);

        assertNotLocked("b".repeat(64));
    }

    @Test
    void accountKeyIsTheSameWhateverIdentifierWasTyped() {
        assertThat(throttle.keyFor(17939477151001L, "quan"))
                .isEqualTo(throttle.keyFor(17939477151001L, "quan@gmail.com"))
                .isNotEqualTo(throttle.keyFor(17939477151002L, "quan"));
    }

    @Test
    void typedIdentifierKeyIsNormalizedLikeARegistration() {
        assertThat(throttle.keyFor(null, " Quan ")).isEqualTo(throttle.keyFor(null, "quan"));
        assertThat(throttle.keyFor(null, "0912.345.678")).isEqualTo(throttle.keyFor(null, "+84912345678"));
        assertThat(throttle.keyFor(null, "quan")).isNotEqualTo(throttle.keyFor(null, "quynh"));
    }

    @Test
    void accountAndTypedIdentifierNeverShareAKey() {
        assertThat(throttle.keyFor(17939477151001L, "x")).isNotEqualTo(throttle.keyFor(null, "17939477151001"));
    }

    @Test
    void keyIsASha256HexThatDoesNotContainTheIdentifier() {
        String key = throttle.keyFor(null, "Matkhau@123");

        assertThat(key).hasSize(64).matches("[0-9a-f]{64}").doesNotContainIgnoringCase("matkhau");
    }

    private void fail(int times) {
        IntStream.range(0, times).forEach(i -> throttle.recordFailure(KEY));
    }

    private void assertLocked(String key) {
        assertThat(catchThrowableOfType(BusinessException.class, () -> throttle.checkNotLocked(key))).isNotNull();
    }

    private void assertNotLocked(String key) {
        assertThatCode(() -> throttle.checkNotLocked(key)).doesNotThrowAnyException();
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
