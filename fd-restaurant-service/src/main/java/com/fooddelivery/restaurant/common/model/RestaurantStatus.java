package com.fooddelivery.restaurant.common.model;

/**
 * Lifecycle of a {@link Restaurant}, stored by name in {@code restaurants.status} (limited by the
 * {@code ck_restaurants_status} constraint, so a new value needs a migration).
 */
public enum RestaurantStatus {
    // Just created by its owner, waiting for an admin to approve it; not shown to customers
    PENDING,
    // Approved and open to customers
    ACTIVE,
    // Hidden from customers by an admin
    SUSPENDED
}
