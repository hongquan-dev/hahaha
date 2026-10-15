package com.fooddelivery.gateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/gateway")
public class GatewayController {

    // Health check endpoint for API Gateway
    @GetMapping("/health")
    public String healthCheck() {
        return "API Gateway Service is running independently on port 8080!";
    }
}