package com.fooddelivery.restaurant.service;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.id.entity.EntityType;
import com.fooddelivery.id.entity.IdUtils;
import com.fooddelivery.restaurant.common.model.Restaurant;
import com.fooddelivery.restaurant.common.model.RestaurantStatus;
import com.fooddelivery.restaurant.dto.CreateRestaurantRequest;
import com.fooddelivery.restaurant.dto.RestaurantDto;
import com.fooddelivery.restaurant.repository.RestaurantRepository;
import com.fooddelivery.web.exception.BusinessException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Business logic of the APIs in {@code RestaurantController}.
 *
 * <p>Input reaching this class has already passed the role check of the API flow and {@code RestaurantValidator}
 * (required fields, format), so it is not checked again here; rules that need the database, such as one restaurant
 * per owner, live here instead.
 */
@Service
public class RestaurantService {
    private static final Logger log = LogManager.getLogger(RestaurantService.class);

    // Unique constraint of restaurants.owner_user_id, as named in V1__create_restaurants.sql
    private static final String OWNER_CONSTRAINT = "uk_restaurants_owner_user_id";

    private final RestaurantRepository restaurantRepository;

    public RestaurantService(RestaurantRepository restaurantRepository) {
        this.restaurantRepository = restaurantRepository;
    }

    /**
     * Creates the restaurant of an owner, in status {@code PENDING} until an admin approves it.
     *
     * <p>Throws {@code BusinessException} 409 {@code RESTAURANT_ALREADY_EXISTS} if the owner already has a restaurant,
     * also when another request of the same owner saves one between the check and the save. Any other database error
     * is thrown as it is.
     *
     * @param ownerUserId user ID of the caller, from the {@code X-User-Id} header set by the gateway
     * @param request     body that passed {@code RestaurantValidator.validateCreate}, not normalized yet
     * @return the created restaurant
     */
    public RestaurantDto create(long ownerUserId, CreateRestaurantRequest request) {
        if (restaurantRepository.existsByOwnerUserId(ownerUserId)) {
            throw alreadyExists();
        }

        CreateRestaurantRequest body = request.normalized();
        Restaurant restaurant = new Restaurant(IdUtils.generate(EntityType.RESTAURANT), ownerUserId, body.name(),
                body.address(), body.phoneNumber(), body.description(), RestaurantStatus.PENDING);
        try {
            // Flushed now so a unique constraint violation is thrown here, inside the try, even if a caller wraps
            // this method in a transaction that would otherwise commit later
            restaurant = restaurantRepository.saveAndFlush(restaurant);
        } catch (DataIntegrityViolationException e) {
            // The check above already looked for a restaurant. This only happens when two requests of the same
            // owner both pass it before either is saved
            if (!isOwnerConstraint(e)) {
                throw e;
            }
            throw alreadyExists();
        }

        log.info("Created restaurant {} '{}' for owner {}", restaurant.getId(), restaurant.getName(), ownerUserId);
        return RestaurantDto.from(restaurant);
    }

    /**
     * Finds the restaurant of an owner.
     *
     * <p>Throws {@code BusinessException} 404 {@code RESTAURANT_NOT_FOUND} if the owner has not created one yet; the
     * owner's screen then offers to create it.
     *
     * @param ownerUserId user ID of the caller, from the {@code X-User-Id} header set by the gateway
     * @return the owner's restaurant
     */
    public RestaurantDto findMine(long ownerUserId) {
        return restaurantRepository.findByOwnerUserId(ownerUserId)
                .map(RestaurantDto::from)
                .orElseThrow(() -> new BusinessException(ApiStatus.NOT_FOUND, "RESTAURANT_NOT_FOUND",
                        "This account has no restaurant yet"));
    }

    private static BusinessException alreadyExists() {
        return new BusinessException(ApiStatus.CONFLICT, "RESTAURANT_ALREADY_EXISTS",
                "This account already has a restaurant");
    }

    // Whether the database rejected the insert because of the unique owner_user_id, not another constraint
    private static boolean isOwnerConstraint(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE
                    && violation.getConstraintName() != null) {
                return OWNER_CONSTRAINT.equals(violation.getConstraintName().toLowerCase(Locale.ROOT));
            }
        }
        return false;
    }
}
