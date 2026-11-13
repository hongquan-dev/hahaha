package com.fooddelivery.auth.dto;

import java.time.Instant;

/**
 * Data of {@code GET /auth/test/ping}: who the access token belongs to and how long it stays valid, so a client can
 * show a countdown and check that an expired token is renewed by {@code POST /auth/refresh}.
 *
 * @param userId     {@code sub} claim of the access token
 * @param role       {@code role} claim, e.g. {@code CUSTOMER}
 * @param issuedAt   when the access token was issued, {@code null} if it has no {@code iat} claim
 * @param expiresAt  when the access token expires; the server still accepts it for {@code TokenService.CLOCK_SKEW}
 *                   after that, but the browser deletes the cookie at this time
 * @param serverTime current time of the server, so a client with a skewed clock can still compute the time left
 */
public record PingResponse(Long userId, String role, Instant issuedAt, Instant expiresAt, Instant serverTime) {
}
