package com.fooddelivery.auth.dto;

import com.fooddelivery.auth.common.model.User;

import java.time.LocalDateTime;

/**
 * User returned by the APIs instead of the {@link User} entity, so the password hash never leaves the service.
 *
 * @param id          14-digit ID from {@code IdUtils}, e.g. {@code 17939313751001}
 * @param username    lowercase
 * @param email       lowercase
 * @param phoneNumber as {@code +84xxxxxxxxx}
 * @param role        e.g. {@code CUSTOMER}
 * @param active      {@code false} if the account is locked
 * @param createdAt   server local time
 */
public record UserDto(
        Long id,
        String username,
        String email,
        String phoneNumber,
        String role,
        boolean active,
        LocalDateTime createdAt
) {
    public static UserDto from(User user) {
        return new UserDto(user.getId(), user.getUsername(), user.getEmail(), user.getPhoneNumber(), user.getRole(),
                user.isActive(), user.getCreatedAt());
    }
}
