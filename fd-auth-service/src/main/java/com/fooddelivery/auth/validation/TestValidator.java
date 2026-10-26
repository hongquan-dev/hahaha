package com.fooddelivery.auth.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fooddelivery.apiflow.validation.ValidationRequest;
import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.repository.UserRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.Optional;

// Validator cho các API test trong TestController.
// api-flow gọi nó trước controller; trả false thì request bị từ chối với 400 REQUEST_VALIDATION_FAILED
@Component
public class TestValidator {
    private static final Logger log = LogManager.getLogger(TestValidator.class);

    private static final String ADMIN_ROLE = "ADMIN";
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 50;

    private final UserRepository userRepository;

    public TestValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // Khớp với <validator>...TestValidator.validateUserSearch</validator> trong api-routes.xml.
    // Lấy thông tin từ cả 4 phần của request: header, query param, body, path variable (kèm truy vấn DB).
    // Cấu trúc body (kiểu dữ liệu, field lạ) đã được schemas/user-search.json kiểm tra trước đó
    public boolean validateUserSearch(ValidationRequest request) {
        log.info("[VALIDATE] [{}] {} | pathVariables={} | queryParams={} | X-Client-Id={} | body={}",
                request.getMethod(), request.getPath(), request.getPathVariables(), request.getQueryParams(),
                request.getHeader("X-Client-Id"), request.getBodyJson());

        // Header: phải cho biết client nào gọi
        String clientId = request.getHeader("X-Client-Id");
        if (clientId == null || clientId.isBlank()) {
            return reject("missing header X-Client-Id");
        }

        // Query param: page >= 0, 1 <= size <= 100; không truyền thì controller dùng giá trị mặc định
        String pageText = request.getQueryParam("page");
        Integer page = parseInt(pageText);
        if (pageText != null && (page == null || page < 0)) {
            return reject("page must be an integer >= 0, got '" + pageText + "'");
        }
        String sizeText = request.getQueryParam("size");
        Integer size = parseInt(sizeText);
        if (sizeText != null && (size == null || size < 1 || size > MAX_PAGE_SIZE)) {
            return reject("size must be an integer from 1 to " + MAX_PAGE_SIZE + ", got '" + sizeText + "'");
        }

        // Body: role nếu có thì không được rỗng, keyword không quá dài
        JsonNode body = request.getBodyJson();
        if (body == null) {
            return reject("body must be a JSON object");
        }
        JsonNode role = body.path("role");
        if (role.isTextual() && role.asText().isBlank()) {
            return reject("role must not be blank");
        }
        JsonNode keyword = body.path("keyword");
        if (keyword.isTextual() && keyword.asText().trim().length() > MAX_KEYWORD_LENGTH) {
            return reject("keyword must be at most " + MAX_KEYWORD_LENGTH + " characters");
        }

        // Path variable + DB: adminId phải là user đang hoạt động, có role ADMIN.
        // Để cuối cùng để không truy vấn DB khi request đã sai ở các bước trên
        String adminIdText = request.getPathVariable("adminId");
        Long adminId = parseLong(adminIdText);
        if (adminId == null) {
            return reject("adminId must be a number, got '" + adminIdText + "'");
        }
        Optional<User> admin = userRepository.findById(adminId);
        if (admin.isEmpty()) {
            return reject("user " + adminId + " does not exist");
        }
        if (!admin.get().isActive() || !ADMIN_ROLE.equalsIgnoreCase(admin.get().getRole())) {
            return reject("user " + adminId + " is not an active " + ADMIN_ROLE);
        }

        return true;
    }

    // Client chỉ nhận REQUEST_VALIDATION_FAILED, nên ghi lý do cụ thể vào log
    private static boolean reject(String reason) {
        log.warn("[VALIDATE] Rejected: {}", reason);
        return false;
    }

    // null nếu không truyền hoặc không phải số nguyên
    private static Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
