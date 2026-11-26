package com.fooddelivery.restaurant.repository;

import com.fooddelivery.restaurant.common.model.Restaurant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Access to the {@code restaurants} table.
 */
@Repository
public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {
    // One restaurant per owner (RestaurantService.create)
    boolean existsByOwnerUserId(Long ownerUserId);

    // The owner's restaurant (RestaurantService.findMine); unique, so at most one
    Optional<Restaurant> findByOwnerUserId(Long ownerUserId);
}
