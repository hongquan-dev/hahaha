package com.fooddelivery.gateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health check of the API gateway: {@code GET /gateway/health} returns a plain-text message while the service is up.
 */
@RestController
@RequestMapping("/gateway")
public class GatewayController {

    @GetMapping("/health")
    public String healthCheck() {
        return "API Gateway Service is running independently on port 8080!";
    }
}