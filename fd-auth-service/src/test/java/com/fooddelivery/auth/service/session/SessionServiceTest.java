package com.fooddelivery.auth.service.session;


import com.fooddelivery.auth.common.model.RefreshToken;
import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.config.properties.AuthProperties;
import com.fooddelivery.auth.repository.RefreshTokenRepository;
import com.fooddelivery.auth.repository.UserRepository;
import com.fooddelivery.auth.service.token.TokenService;
import com.fooddelivery.web.exception.BusinessException;
import com.fooddelivery.web.status.ApiStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the refresh rules of {@link SessionService}, with mocked repositories and token service and a fixed
 * clock. Concurrency (the row lock) is covered by running the real service, not here.
 */
class SessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T08:00:00Z");
    private static final long USER_ID = 17939477151001L;
    private static final long FAMILY_ID = 17939477156001L;
    private static final String RAW = "raw-token";
    private static final String HASH = "hash-of-raw-token";

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final TokenService tokenService = mock(TokenService.class);
    private SessionService sessionService;
    private User user;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties(
                new AuthProperties.Jwt("unused", "unused", "kid", "fd-auth-service", Duration.ofMinutes(15)),
                new AuthProperties.Refresh(Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(10)),
                new AuthProperties.Cookie(true),
                List.of("http://localhost:5173"));
        sessionService = new SessionService(refreshTokenRepository, userRepository, tokenService, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        user = new User(USER_ID, "quan", "hash", "quan@test.com", "+84912345678", "CUSTOMER");

        when(tokenService.hash(RAW)).thenReturn(HASH);
        when(tokenService.hash("next-raw")).thenReturn("next-hash");
        when(tokenService.generateRefreshToken()).thenReturn("next-raw");
        when(tokenService.issueAccessToken(any())).thenReturn("access-jwt");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void startCreatesAFamilyWhoseIdIsTheFirstTokenId() {
        IssuedTokens tokens = sessionService.start(user, "UA", "127.0.0.1");

        RefreshToken saved = savedToken();
        assertThat(saved.getFamilyId()).isEqualTo(saved.getId());
        assertThat(saved.getTokenHash()).isEqualTo("next-hash");
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(saved.getFamilyExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(tokens.refreshToken()).isEqualTo("next-raw");
        assertThat(tokens.accessToken()).isEqualTo("access-jwt");
    }

    @Test
    void startTruncatesALongUserAgent() {
        sessionService.start(user, "x".repeat(600), "127.0.0.1");

        assertThat(savedToken().getUserAgent()).hasSize(512);
    }

    @Test
    void refreshRotatesTheTokenInTheSameFamily() {
        // Created 3 days ago: 4 days of idle time left, 20 days of family left
        RefreshToken current = token(NOW.plus(Duration.ofDays(4)), NOW.plus(Duration.ofDays(20)));
        stored(current);

        IssuedTokens tokens = sessionService.refresh(RAW, "UA", "127.0.0.1");

        assertThat(current.getRevokedAt()).isEqualTo(NOW);
        assertThat(current.getReplacedAt()).isEqualTo(NOW);
        RefreshToken next = savedToken();
        assertThat(next.getFamilyId()).isEqualTo(FAMILY_ID);
        assertThat(next.getFamilyExpiresAt()).isEqualTo(current.getFamilyExpiresAt());
        assertThat(next.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(tokens.familyExpiresAt()).isEqualTo(current.getFamilyExpiresAt());
    }

    @Test
    void missingOrUnknownTokenIsInvalid() {
        assertError(() -> sessionService.refresh(null, null, "ip"), ApiStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
        assertError(() -> sessionService.refresh(" ", null, "ip"), ApiStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
        when(refreshTokenRepository.findByTokenHashForUpdate(HASH)).thenReturn(Optional.empty());
        assertError(() -> sessionService.refresh(RAW, null, "ip"), ApiStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
    }

    @Test
    void reuseWithinGraceIsAConflictAndRevokesNothing() {
        RefreshToken current = token(NOW.plus(Duration.ofDays(7)), NOW.plus(Duration.ofDays(30)));
        current.setRevokedAt(NOW.minusSeconds(10));
        current.setReplacedAt(NOW.minusSeconds(10));
        stored(current);

        assertError(() -> sessionService.refresh(RAW, null, "ip"), ApiStatus.CONFLICT, "REFRESH_CONFLICT");
        verify(refreshTokenRepository, never()).revokeFamily(anyLong(), any());
    }

    @Test
    void reuseAfterGraceRevokesTheWholeFamily() {
        RefreshToken current = token(NOW.plus(Duration.ofDays(7)), NOW.plus(Duration.ofDays(30)));
        current.setRevokedAt(NOW.minusSeconds(11));
        current.setReplacedAt(NOW.minusSeconds(11));
        stored(current);

        assertError(() -> sessionService.refresh(RAW, null, "ip"), ApiStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED");
        verify(refreshTokenRepository).revokeFamily(FAMILY_ID, NOW);
    }

    @Test
    void tokenRevokedByLogoutIsInvalidNotReused() {
        RefreshToken current = token(NOW.plus(Duration.ofDays(7)), NOW.plus(Duration.ofDays(30)));
        current.setRevokedAt(NOW.minus(Duration.ofHours(1)));
        stored(current);

        assertError(() -> sessionService.refresh(RAW, null, "ip"), ApiStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
        verify(refreshTokenRepository, never()).revokeFamily(anyLong(), any());
    }

    @Test
    void idleOrFamilyExpiryIsInvalid() {
        stored(token(NOW.minusSeconds(1), NOW.plus(Duration.ofDays(30))));
        assertError(() -> sessionService.refresh(RAW, null, "ip"), ApiStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");

        stored(token(NOW.plus(Duration.ofDays(7)), NOW.minusSeconds(1)));
        assertError(() -> sessionService.refresh(RAW, null, "ip"), ApiStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
    }

    @Test
    void disabledUserRevokesTheFamily() {
        user.setActive(false);
        stored(token(NOW.plus(Duration.ofDays(7)), NOW.plus(Duration.ofDays(30))));

        assertError(() -> sessionService.refresh(RAW, null, "ip"), ApiStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
        verify(refreshTokenRepository).revokeFamily(FAMILY_ID, NOW);
    }

    @Test
    void endRevokesTheFamilyAndIgnoresMissingTokens() {
        sessionService.end(null);
        when(refreshTokenRepository.findByTokenHash(HASH)).thenReturn(Optional.empty());
        sessionService.end(RAW);
        verify(refreshTokenRepository, never()).revokeFamily(anyLong(), any());

        when(refreshTokenRepository.findByTokenHash(HASH))
                .thenReturn(Optional.of(token(NOW.plus(Duration.ofDays(7)), NOW.plus(Duration.ofDays(30)))));
        sessionService.end(RAW);
        verify(refreshTokenRepository).revokeFamily(FAMILY_ID, NOW);
    }

    private RefreshToken token(Instant expiresAt, Instant familyExpiresAt) {
        return new RefreshToken(FAMILY_ID, USER_ID, HASH, FAMILY_ID, expiresAt, familyExpiresAt, "UA", "127.0.0.1");
    }

    private void stored(RefreshToken token) {
        when(refreshTokenRepository.findByTokenHashForUpdate(HASH)).thenReturn(Optional.of(token));
    }

    private RefreshToken savedToken() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        return captor.getValue();
    }

    private static void assertError(Runnable call, ApiStatus status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getCode()).isEqualTo(code);
                });
    }
}
