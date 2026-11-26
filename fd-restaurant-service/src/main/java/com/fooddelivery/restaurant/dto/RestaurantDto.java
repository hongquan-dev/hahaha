package com.fooddelivery.restaurant.dto;

import com.fooddelivery.restaurant.common.model.Restaurant;

import java.time.LocalDateTime;

/**
 * Restaurant returned by the APIs instead of the {@link Restaurant} entity.
 *
 * @param id          14-digit ID from {@code IdUtils}, e.g. {@code 17939522302001}
 * @param ownerUserId user ID of the owner account in auth-service
 * @param name        trimmed
 * @param address     trimmed
 * @param phoneNumber as {@code +84xxxxxxxxx}
 * @param description trimmed; {@code null} if none
 * @param status      name of a {@code RestaurantStatus}, e.g. {@code PENDING}
 * @param createdAt   server local time
 */
public record RestaurantDto(
        Long id,
        Long ownerUserId,
        String name,
        String address,
        String phoneNumber,
        String description,
        String status,
        LocalDateTime createdAt
) {
    public static RestaurantDto from(Restaurant restaurant) {
        return new RestaurantDto(restaurant.getId(), restaurant.getOwnerUserId(), restaurant.getName(),
                restaurant.getAddress(), restaurant.getPhoneNumber(), restaurant.getDescription(),
                restaurant.getStatus().name(), restaurant.getCreatedAt());
    }
}
