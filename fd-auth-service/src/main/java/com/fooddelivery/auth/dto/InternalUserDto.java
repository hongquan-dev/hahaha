package com.fooddelivery.auth.dto;

import com.fooddelivery.auth.common.model.User;

/**
 * User returned to other services by the gRPC {@code UserService.GetUser}, mapped to {@code UserResponse} by
 * {@code UserGrpcService}.
 *
 * <p>Only what another service needs to show or check a user, e.g. the customer of an order: no password hash, no
 * email, no timestamps.
 *
 * @param id          14-digit ID from {@code IdUtils}, e.g. {@code 17939313751001}
 * @param username    lowercase
 * @param phoneNumber as {@code +84xxxxxxxxx}; {@code null} if the account has none
 * @param role        e.g. {@code CUSTOMER}
 * @param active      {@code false} if the account is locked
 */
public record InternalUserDto(Long id, String username, String phoneNumber, String role, boolean active) {

    public static InternalUserDto from(User user) {
        return new InternalUserDto(user.getId(), user.getUsername(), user.getPhoneNumber(), user.getRole(),
                user.isActive());
    }
}
