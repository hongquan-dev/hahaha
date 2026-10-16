package com.fooddelivery.restaurant.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/restaurant")
public class RestaurantController {

    @GetMapping("/health")
    public String healthCheck() {
        return "Restaurant Service is running independently on port 8082!";
    }
}