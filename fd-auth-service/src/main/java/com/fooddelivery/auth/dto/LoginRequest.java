package com.fooddelivery.auth.dto;

/**
 * Body of {@code POST /auth/login}, read with {@code request.getBody(LoginRequest.class)}.
 *
 * <p>Checked in three steps: structure by {@code schemas/login.json}, required fields and length by
 * {@code AuthValidator.validateLogin}, the account and password by {@code AuthService.login}.
 *
 * @param identifier username, email or Vietnamese mobile number, in any form accepted at registration, e.g.
 *                   {@code " Quan@Gmail.com "} or {@code 0912.345.678}; normalized by {@code AuthService.login}
 * @param password   compared as typed, never trimmed
 */
public record LoginRequest(
        String identifier,
        String password
) {
}
