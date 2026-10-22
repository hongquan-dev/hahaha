package com.fooddelivery.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fooddelivery.web.handler.ResponseHandler;
import com.fooddelivery.web.model.ApiResponse;
import com.fooddelivery.web.model.FieldErrorDetail;
import com.fooddelivery.web.status.ApiStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth/test")
public class TestController {

    // ===== Luồng api-flow: schema (schemas/test-item.json) -> validator (TestValidator.validateItem) -> controller =====

    // POST /auth/test/items -> 201
    @PostMapping("/items")
    public ResponseEntity<ApiResponse<JsonNode>> createItem(@RequestBody JsonNode body) {
        return ResponseHandler.generateResponse(ApiStatus.CREATED, ApiResponse.success(body));
    }

    // ===== Các trường hợp trả lỗi bằng ResponseHandler.generateErrorResponse =====

    // Cùng một method trả cả thành công lẫn lỗi: id 1-5 -> 200, id khác -> 404 với mã riêng ORDER_NOT_FOUND
    @GetMapping("/errors/orders/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> order(@PathVariable Long id) {
        if (id < 1 || id > 5) {
            return ResponseHandler.generateErrorResponse(ApiStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order " + id + " not found", null);
        }
        return ResponseHandler.generateResponse(ApiStatus.OK, ApiResponse.success(Map.of("id", id, "food", "Món " + id)));
    }

    // Chỉ truyền status -> code NOT_FOUND, message "Not Found"
    @GetMapping("/errors/status-only")
    public ResponseEntity<ApiResponse<Void>> statusOnly() {
        return ResponseHandler.generateErrorResponse(ApiStatus.NOT_FOUND);
    }

    // 400, lỗi chung không gắn với field -> code BAD_REQUEST
    @GetMapping("/errors/bad-request")
    public ResponseEntity<ApiResponse<Void>> badRequest() {
        return ResponseHandler.generateErrorResponse(ApiStatus.BAD_REQUEST, "Report is only available after 6 PM");
    }

    // 400 kèm lỗi theo từng field -> code BAD_REQUEST. minPrice/maxPrice âm hoặc minPrice > maxPrice. Hợp lệ -> 200
    @GetMapping("/errors/validation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validation(@RequestParam(required = false) Integer minPrice,
                                                                       @RequestParam(required = false) Integer maxPrice) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (minPrice != null && minPrice < 0) {
            errors.add(new FieldErrorDetail("minPrice", "must not be negative"));
        }
        if (maxPrice != null && maxPrice < 0) {
            errors.add(new FieldErrorDetail("maxPrice", "must not be negative"));
        }
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            errors.add(new FieldErrorDetail("minPrice", "must not be greater than maxPrice"));
        }
        if (!errors.isEmpty()) {
            return ResponseHandler.generateErrorResponse(ApiStatus.BAD_REQUEST, "Request data is invalid", errors);
        }
        return ResponseHandler.generateResponse(ApiStatus.OK, ApiResponse.success(Map.of("minPrice", String.valueOf(minPrice),
                "maxPrice", String.valueOf(maxPrice))));
    }

    // Chỉ truyền status + lỗi theo field -> 400, code BAD_REQUEST, message mặc định "Bad Request".
    // Thiếu username/email -> lỗi; đủ cả hai -> 200
    @GetMapping("/errors/field-errors")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fieldErrors(@RequestParam(required = false) String username,
                                                                        @RequestParam(required = false) String email) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (username == null || username.isBlank()) {
            errors.add(new FieldErrorDetail("username", "is required"));
        }
        if (email == null || email.isBlank()) {
            errors.add(new FieldErrorDetail("email", "is required"));
        }
        if (!errors.isEmpty()) {
            return ResponseHandler.generateErrorResponse(ApiStatus.BAD_REQUEST, errors);
        }
        return ResponseHandler.generateResponse(ApiStatus.OK, ApiResponse.success(Map.of("username", username, "email", email)));
    }

    // 401 -> code UNAUTHORIZED
    @GetMapping("/errors/unauthorized")
    public ResponseEntity<ApiResponse<Void>> unauthorized() {
        return ResponseHandler.generateErrorResponse(ApiStatus.UNAUTHORIZED, "Token is invalid or expired");
    }

    // 403 -> code FORBIDDEN
    @GetMapping("/errors/forbidden")
    public ResponseEntity<ApiResponse<Void>> forbidden() {
        return ResponseHandler.generateErrorResponse(ApiStatus.FORBIDDEN, "You do not have permission to view this order");
    }

    // 409 -> code CONFLICT
    @GetMapping("/errors/conflict")
    public ResponseEntity<ApiResponse<Void>> conflict() {
        return ResponseHandler.generateErrorResponse(ApiStatus.CONFLICT, "Username already exists");
    }

    // 429 kèm header Retry-After: 30, code null -> TOO_MANY_REQUESTS
    @GetMapping("/errors/too-many-requests")
    public ResponseEntity<ApiResponse<Void>> tooManyRequests() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "30");
        return ResponseHandler.generateErrorResponse(ApiStatus.TOO_MANY_REQUESTS, headers, null,
                "Too many requests, try again in 30 seconds", null);
    }

    // 500, không truyền message -> code INTERNAL_ERROR, message mặc định "Internal Server Error"
    @GetMapping("/errors/internal")
    public ResponseEntity<ApiResponse<Void>> internal() {
        return ResponseHandler.generateErrorResponse(ApiStatus.INTERNAL_SERVER_ERROR);
    }

    // 503 -> code SERVICE_UNAVAILABLE
    @GetMapping("/errors/unavailable")
    public ResponseEntity<ApiResponse<Void>> unavailable() {
        return ResponseHandler.generateErrorResponse(ApiStatus.SERVICE_UNAVAILABLE, "Payment provider is not responding");
    }

    // ===== Dùng sai: lib báo lỗi (IllegalArgumentException) -> Spring trả 500 theo format mặc định =====

    // generateErrorResponse với status thành công
    @GetMapping("/errors/misuse/error-with-200")
    public ResponseEntity<ApiResponse<Void>> errorWith200() {
        return ResponseHandler.generateErrorResponse(ApiStatus.OK, "This must not be allowed");
    }

    // generateResponse với status lỗi
    @GetMapping("/errors/misuse/success-with-404")
    public ResponseEntity<ApiResponse<Void>> successWith404() {
        return ResponseHandler.generateResponse(ApiStatus.NOT_FOUND, ApiResponse.ok());
    }
}
