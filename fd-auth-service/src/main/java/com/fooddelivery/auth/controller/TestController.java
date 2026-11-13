package com.fooddelivery.auth.controller;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.auth.dto.PingResponse;
import com.fooddelivery.auth.service.test.PingService;
import com.fooddelivery.auth.web.AuthCookies;
import com.fooddelivery.dto.response.ApiResponse;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.web.handler.ResponseHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test APIs of the auth service, under {@code /auth/test}, for checking login sessions from the frontend. They only
 * read the request and change nothing.
 */
@RestController
@RequestMapping("/auth/test")
public class TestController {

    private final PingService pingService;

    public TestController(PingService pingService) {
        this.pingService = pingService;
    }

    /**
     * {@code GET /auth/test/ping}: returns 200 with the owner and lifetime of the {@code access_token} cookie, or 401
     * {@code TOKEN_EXPIRED} / {@code UNAUTHENTICATED}, after which the client calls {@code POST /auth/refresh} once.
     *
     * <p>Flow: this method -> {@link PingService#ping} (token).
     */
    @GetMapping("/ping")
    public ResponseEntity<ApiResponse<PingResponse>> ping(ApiRequest request) {
        PingResponse ping = pingService.ping(request.getCookie(AuthCookies.ACCESS_TOKEN_COOKIE).asString());
        return ResponseHandler.generateResponse(ApiStatus.OK, ApiResponse.success(ping));
    }
}
