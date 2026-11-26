package com.fooddelivery.restaurant.controller;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.dto.response.ApiResponse;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.restaurant.dto.CreateRestaurantRequest;
import com.fooddelivery.restaurant.dto.RestaurantDto;
import com.fooddelivery.restaurant.service.RestaurantService;
import com.fooddelivery.web.handler.ResponseHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * APIs of the restaurant service, under {@code /restaurant}: the health check, and creating and reading the restaurant
 * of the caller.
 *
 * <p>Each method receives the same {@code ApiRequest} instance that the route's validator checked, after the API flow
 * has checked the caller's role; the business rules are left to {@link RestaurantService}.
 */
@RestController
@RequestMapping("/restaurant")
public class RestaurantController {

    private final RestaurantService restaurantService;

    public RestaurantController(RestaurantService restaurantService) {
        this.restaurantService = restaurantService;
    }

    // Plain-text message while the service is up
    @GetMapping("/health")
    public String healthCheck() {
        return "Restaurant Service is running independently on port 8082!";
    }

    /**
     * {@code POST /restaurant/restaurants}: creates the caller's restaurant and returns it with 201.
     *
     * <p>Flow: gateway {@code JwtAuth} (token) -> {@code <roles>RESTAURANT_OWNER</roles>} (role) ->
     * {@code schemas/create-restaurant.json} (structure) -> {@code RestaurantValidator.validateCreate} (required
     * fields, format) -> this method -> {@link RestaurantService#create} (one per owner, save). The owner is the
     * caller, never a field of the body.
     */
    @PostMapping("/restaurants")
    public ResponseEntity<ApiResponse<RestaurantDto>> create(ApiRequest request) {
        // Never null here: the role check rejected requests without a user, and the gateway writes a numeric ID
        long ownerUserId = request.getUserId().asLong();
        RestaurantDto restaurant = restaurantService.create(ownerUserId,
                request.getBody(CreateRestaurantRequest.class));
        return ResponseHandler.generateResponse(ApiStatus.CREATED, ApiResponse.success(restaurant));
    }

    /**
     * {@code GET /restaurant/restaurants/me}: returns the caller's restaurant with 200.
     *
     * <p>Flow: gateway {@code JwtAuth} (token) -> {@code <roles>RESTAURANT_OWNER</roles>} (role) -> this method ->
     * {@link RestaurantService#findMine} (404 {@code RESTAURANT_NOT_FOUND} if none yet).
     */
    @GetMapping("/restaurants/me")
    public ResponseEntity<ApiResponse<RestaurantDto>> findMine(ApiRequest request) {
        // Never null here, as in create
        long ownerUserId = request.getUserId().asLong();
        return ResponseHandler.generateResponse(ApiStatus.OK,
                ApiResponse.success(restaurantService.findMine(ownerUserId)));
    }
}
