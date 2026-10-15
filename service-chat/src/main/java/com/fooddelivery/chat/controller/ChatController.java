package com.fooddelivery.chat.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
public class ChatController {

    @GetMapping("/health")
    public String healthCheck() {
        return "Chat Service is running independently on port 8085!";
    }
}