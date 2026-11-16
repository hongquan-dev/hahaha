package com.fooddelivery.order.client;

/**
 * A user as auth-service returns it, whichever transport fetched it: {@link AuthHttpClient} reads it from the
 * {@code data} of {@code GET /internal/users/{userId}}, {@link AuthGrpcClient} maps it from {@code UserResponse}.
 *
 * @param id          14-digit ID, e.g. {@code 17939313751001}
 * @param username    lowercase
 * @param phoneNumber as {@code +84xxxxxxxxx}; {@code null} if the account has none
 * @param role        e.g. {@code CUSTOMER}
 * @param active      {@code false} if the account is locked
 */
public record AuthUser(Long id, String username, String phoneNumber, String role, boolean active) {
}
