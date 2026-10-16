package com.fooddelivery.delivery.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/delivery")
public class DeliveryController {

    @GetMapping("/health")
    public String healthCheck() {
        return "Delivery Service is running independently on port 8084!";
    }
}