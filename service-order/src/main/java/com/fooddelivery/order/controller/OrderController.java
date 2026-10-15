package com.fooddelivery.order.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/order")
public class OrderController {

    @GetMapping("/health")
    public String healthCheck() {
        return "Order Service is running independently on port 8083!";
    }
}