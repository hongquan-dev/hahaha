package com.fooddelivery.order.client;

/**
 * A user as auth-service returns it, mapped by {@link AuthGrpcClient} from the gRPC {@code UserResponse}, so the rest of
 * order-service never sees protobuf classes.
 *
 * @param id          14-digit ID, e.g. {@code 17939313751001}
 * @param username    lowercase
 * @param phoneNumber as {@code +84xxxxxxxxx}; {@code null} if the account has none
 * @param role        e.g. {@code CUSTOMER}
 * @param active      {@code false} if the account is locked
 */
public record AuthUser(Long id, String username, String phoneNumber, String role, boolean active) {
}
