package com.fooddelivery.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// API test cho luồng xác thực của common-api-flow-lib (path tĩnh và path có biến)
@RestController
@RequestMapping("/auth/test")
public class TestController {

    // Path tĩnh: POST /auth/test/items
    @PostMapping("/items")
    public ResponseEntity<?> createItem(@RequestBody JsonNode body) {
        return ResponseEntity.ok(Map.of("message", "Create item OK", "body", body));
    }

    // Path có biến: POST /auth/test/items/{id}
    @PostMapping("/items/{id}")
    public ResponseEntity<?> updateItem(@PathVariable Long id, @RequestBody JsonNode body) {
        return ResponseEntity.ok(Map.of("message", "Update item OK", "id", id, "body", body));
    }
}
