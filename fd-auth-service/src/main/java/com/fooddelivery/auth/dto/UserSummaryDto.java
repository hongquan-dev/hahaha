package com.fooddelivery.auth.dto;

import com.fooddelivery.auth.common.model.User;

import java.time.LocalDateTime;

// Thông tin user trả ra API. Không chứa password, nên không bao giờ trả thẳng entity User
public record UserSummaryDto(
        Long id,
        String username,
        String email,
        String phoneNumber,
        String role,
        boolean active,
        LocalDateTime createdAt
) {
    public static UserSummaryDto from(User user) {
        return new UserSummaryDto(user.getId(), user.getUsername(), user.getEmail(), user.getPhoneNumber(),
                user.getRole(), user.isActive(), user.getCreatedAt());
    }
}
