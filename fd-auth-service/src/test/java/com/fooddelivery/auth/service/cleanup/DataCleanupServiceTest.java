package com.fooddelivery.auth.service.cleanup;

import com.fooddelivery.auth.config.properties.CleanupProperties;
import com.fooddelivery.auth.config.properties.LoginThrottleProperties;
import com.fooddelivery.auth.repository.LoginFailureRepository;
import com.fooddelivery.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link DataCleanupService} with mocked repositories and a fixed clock: which cutoffs it deletes with
 * and how it repeats batches. The SQL of the deletes is checked against PostgreSQL, not here.
 */
class DataCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T08:00:00Z");

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final LoginFailureRepository loginFailureRepository = mock(LoginFailureRepository.class);
    private final DataCleanupService service = new DataCleanupService(refreshTokenRepository, loginFailureRepository,
            new CleanupProperties(Duration.ofDays(7), 100),
            new LoginThrottleProperties(10, Duration.ofMinutes(15), Duration.ofMinutes(5)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void sessionsAreDeletedOnceTheyEndedMoreThanTheRetentionAgo() {
        service.cleanUp();

        verify(refreshTokenRepository).deleteFamiliesEndedBefore(NOW.minus(Duration.ofDays(7)), 100);
    }

    @Test
    void loginFailuresAreDeletedOnceTheirLockAndWindowEnded() {
        service.cleanUp();

        verify(loginFailureRepository).deleteExpired(NOW, NOW.minus(Duration.ofMinutes(15)), 100);
    }

    @Test
    void batchesRepeatUntilNothingIsLeftAndAddUp() {
        when(refreshTokenRepository.deleteFamiliesEndedBefore(any(), anyInt())).thenReturn(250, 40, 0);
        when(loginFailureRepository.deleteExpired(any(), any(), anyInt())).thenReturn(100, 3, 0);

        DataCleanupService.CleanupResult result = service.cleanUp();

        assertThat(result.refreshTokens()).isEqualTo(290);
        assertThat(result.loginFailures()).isEqualTo(103);
    }

    @Test
    void nothingDueDeletesNothing() {
        DataCleanupService.CleanupResult result = service.cleanUp();

        assertThat(result.refreshTokens()).isZero();
        assertThat(result.loginFailures()).isZero();
    }
}
