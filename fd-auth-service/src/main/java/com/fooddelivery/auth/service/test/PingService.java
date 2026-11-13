package com.fooddelivery.auth.service.test;

import com.fooddelivery.auth.dto.PingResponse;
import com.fooddelivery.auth.service.auth.AuthService;
import com.fooddelivery.auth.service.token.AccessTokenVerification;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Logic of the test API {@code GET /auth/test/ping}: a protected call with no side effect, used by the frontend to
 * check that the access token is sent and accepted, and that an expired one is renewed through the refresh token.
 */
@Service
public class PingService {

    private final AuthService authService;
    private final Clock clock;

    public PingService(AuthService authService, Clock clock) {
        this.authService = authService;
        this.clock = clock;
    }

    /**
     * Describes the access token of the request.
     *
     * <p>Throws {@code BusinessException} 401 {@code TOKEN_EXPIRED} or {@code UNAUTHENTICATED}, as
     * {@link AuthService#authenticate}.
     *
     * @param accessToken value of the {@code access_token} cookie, {@code null} if absent
     * @return owner and lifetime of the token, with the current server time
     */
    public PingResponse ping(String accessToken) {
        AccessTokenVerification token = authService.authenticate(accessToken);
        return new PingResponse(token.userId(), token.role(), token.issuedAt(), token.expiresAt(), clock.instant());
    }
}
