package com.fooddelivery.order.controller;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.dto.response.ApiResponse;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.order.dto.AuthCallResult;
import com.fooddelivery.order.service.AuthCallTestService;
import com.fooddelivery.order.validation.AuthTestValidator;
import com.fooddelivery.web.handler.ResponseHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test APIs of order-service, under {@code /order/test}: each fetches the logged-in user from auth-service, one over
 * HTTP and one over gRPC, so the two transports can be compared. They change nothing.
 *
 * <p>Reached through the gateway, whose {@code JwtAuth} filter checks the access token and sets {@code X-User-Id};
 * order-service itself never sees the token.
 */
@RestController
@RequestMapping("/order/test")
public class AuthTestController {

    private final AuthCallTestService authCallTestService;

    public AuthTestController(AuthCallTestService authCallTestService) {
        this.authCallTestService = authCallTestService;
    }

    /**
     * {@code GET /order/test/auth-http}: the caller fetched from {@code GET /internal/users/{userId}} of auth-service,
     * with 200.
     *
     * <p>Flow: gateway {@code JwtAuth} (token) -> {@code AuthTestValidator.validateCaller} ({@code X-User-Id}) -> this
     * method -> {@link AuthCallTestService#callOverHttp} -> {@code AuthHttpClient}.
     */
    @GetMapping("/auth-http")
    public ResponseEntity<ApiResponse<AuthCallResult>> authOverHttp(ApiRequest request) {
        AuthCallResult result = authCallTestService.callOverHttp(userId(request));
        return ResponseHandler.generateResponse(ApiStatus.OK, ApiResponse.success(result));
    }

    /**
     * {@code GET /order/test/auth-grpc}: the caller fetched from the gRPC {@code UserService/GetUser} of auth-service,
     * with 200.
     *
     * <p>Flow: gateway {@code JwtAuth} (token) -> {@code AuthTestValidator.validateCaller} ({@code X-User-Id}) -> this
     * method -> {@link AuthCallTestService#callOverGrpc} -> {@code AuthGrpcClient}.
     */
    @GetMapping("/auth-grpc")
    public ResponseEntity<ApiResponse<AuthCallResult>> authOverGrpc(ApiRequest request) {
        AuthCallResult result = authCallTestService.callOverGrpc(userId(request));
        return ResponseHandler.generateResponse(ApiStatus.OK, ApiResponse.success(result));
    }

    // Already checked by AuthTestValidator: 14 digits
    private static long userId(ApiRequest request) {
        return Long.parseLong(request.getHeader(AuthTestValidator.USER_ID_HEADER).asString());
    }
}
