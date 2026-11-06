package com.fooddelivery.auth.service.session;

import java.time.Instant;

/**
 * Tokens handed out by a login or a refresh, for the controller to put in the cookies with {@code AuthCookies}. Never
 * serialized into a response body.
 *
 * @param accessToken     signed JWT for the {@code access_token} cookie
 * @param refreshToken    raw refresh token for the {@code refresh_token} cookie; only its hash is stored
 * @param expiresAt       idle expiry of the refresh token
 * @param familyExpiresAt absolute expiry of its session
 */
public record IssuedTokens(String accessToken, String refreshToken, Instant expiresAt, Instant familyExpiresAt) {
}
