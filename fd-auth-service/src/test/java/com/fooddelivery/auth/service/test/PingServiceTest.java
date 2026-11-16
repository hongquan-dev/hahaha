package com.fooddelivery.auth.service.test;

import com.fooddelivery.auth.dto.PingResponse;
import com.fooddelivery.auth.service.auth.AuthService;
import com.fooddelivery.auth.service.token.AccessTokenVerification;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link PingService#ping}, with a mocked {@link AuthService} and a fixed {@link Clock} for the server
 * time.
 */
class PingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T08:05:00Z");
    private static final Instant ISSUED_AT = Instant.parse("2026-11-06T08:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-06T08:15:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final PingService pingService = new PingService(authService, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void describesTheTokenOwnerAndLifetimeWithTheServerTime() {
        when(authService.authenticate("token"))
                .thenReturn(AccessTokenVerification.valid(17939477151001L, "CUSTOMER", ISSUED_AT, EXPIRES_AT));

        assertThat(pingService.ping("token"))
                .isEqualTo(new PingResponse(17939477151001L, "CUSTOMER", ISSUED_AT, EXPIRES_AT, NOW));
    }

    @Test
    void tokenWithoutIssuedAtGivesNullIssuedAt() {
        when(authService.authenticate("token"))
                .thenReturn(AccessTokenVerification.valid(17939477151001L, "CUSTOMER", null, EXPIRES_AT));

        assertThat(pingService.ping("token").issuedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TOKEN_EXPIRED", "UNAUTHENTICATED"})
    void authenticationErrorIsPassedOn(String code) {
        BusinessException error = new BusinessException(ApiStatus.UNAUTHORIZED, code, "Login required");
        when(authService.authenticate("token")).thenThrow(error);

        assertThatThrownBy(() -> pingService.ping("token")).isSameAs(error);
    }
}
