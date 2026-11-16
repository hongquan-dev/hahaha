package com.fooddelivery.auth.controller;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.auth.dto.InternalUserDto;
import com.fooddelivery.auth.service.user.UserQueryService;
import com.fooddelivery.dto.response.ApiResponse;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.web.handler.ResponseHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * APIs for other services, under {@code /internal}, e.g. order-service reading the user of an order. The HTTP
 * counterpart of the gRPC {@code UserService}, with the same data.
 *
 * <p>Not reachable from outside: the gateway has no route for {@code /internal/**}, so only callers inside the network
 * reach port 8081. There is no caller authentication yet; it relies on that port not being exposed.
 */
@RestController
@RequestMapping("/internal")
public class InternalUserController {

    private final UserQueryService userQueryService;

    public InternalUserController(UserQueryService userQueryService) {
        this.userQueryService = userQueryService;
    }

    /**
     * {@code GET /internal/users/{userId}}: returns the user with 200, active or locked.
     *
     * <p>Flow: {@code InternalUserValidator.validateGetUser} (14-digit user ID) -> this method ->
     * {@link UserQueryService#getById} (404 {@code USER_NOT_FOUND}).
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<InternalUserDto>> getUser(ApiRequest request) {
        InternalUserDto user = userQueryService.getById(Long.parseLong(request.getPathVariable("userId").asString()));
        return ResponseHandler.generateResponse(ApiStatus.OK, ApiResponse.success(user));
    }
}
