package com.fooddelivery.auth.controller;

import com.fooddelivery.apiflow.validation.ValidationRequest;
import com.fooddelivery.web.handler.ResponseHandler;
import com.fooddelivery.web.model.ApiResponse;
import com.fooddelivery.web.status.ApiStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth/test")
public class TestController {

    // Luồng api-flow: schema (schemas/test-item.json) -> validator (TestValidator.validateItem, chỉ in log) -> controller
    // Trả lại những gì nhận được từ ValidationRequest để đối chiếu với log của validator
    // POST /auth/test/users/{userId}/items -> 201
    @PostMapping("/users/{userId}/items")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createItem(ValidationRequest request) {
        // LinkedHashMap: giữ thứ tự và cho phép giá trị null (vd. thiếu header)
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pathVariables", request.getPathVariables());
        data.put("queryParams", request.getQueryParams());
        data.put("clientId", request.getHeader("X-Client-Id"));
        data.put("body", request.getBodyJson());
        return ResponseHandler.generateResponse(ApiStatus.CREATED, ApiResponse.success(data));
    }
}
