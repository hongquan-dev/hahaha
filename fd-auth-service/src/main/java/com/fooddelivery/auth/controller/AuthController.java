package com.fooddelivery.auth.controller;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.auth.dto.RegisterRequest;
import com.fooddelivery.auth.dto.UserDto;
import com.fooddelivery.auth.service.AuthService;
import com.fooddelivery.web.handler.ResponseHandler;
import com.fooddelivery.web.model.ApiResponse;
import com.fooddelivery.web.status.ApiStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Account APIs of the auth service, under {@code /auth}.
 *
 * <p>Each method receives the same {@code ApiRequest} instance that the route's validator checked, so values read from
 * it are already valid; the business rules are left to {@link AuthService}.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    private static final Logger log = LogManager.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * {@code POST /auth/register}: creates an account and returns it with 201.
     *
     * <p>Flow: {@code schemas/register.json} (structure) -> {@code AuthValidator.validateRegister} (required fields,
     * format) -> this method -> {@link AuthService#register} (duplicates, save). The user is not logged in: no cookie
     * is set, the client calls the login API afterwards.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserDto>> register(ApiRequest request) {
        UserDto user = authService.register(request.getBody(RegisterRequest.class));
        return ResponseHandler.generateResponse(ApiStatus.CREATED, ApiResponse.success(user));
    }
}
