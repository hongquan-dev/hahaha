package com.fooddelivery.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.apiflow.validation.ValidationRequest;
import com.fooddelivery.auth.dto.UserSearchRequest;
import com.fooddelivery.auth.dto.UserSummaryDto;
import com.fooddelivery.auth.service.TestService;
import com.fooddelivery.web.handler.ResponseHandler;
import com.fooddelivery.web.model.ApiResponse;
import com.fooddelivery.web.model.PageMeta;
import com.fooddelivery.web.status.ApiStatus;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/auth/test")
public class TestController {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 10;

    private final TestService testService;
    private final ObjectMapper objectMapper;

    public TestController(TestService testService, ObjectMapper objectMapper) {
        this.testService = testService;
        this.objectMapper = objectMapper;
    }

    // Luồng: schema (schemas/user-search.json) -> validator (TestValidator.validateUserSearch, có truy vấn DB)
    // -> controller -> service -> repository.
    // POST /auth/test/admins/{adminId}/users/search?page=0&size=10, header X-Client-Id -> 200, danh sách có phân trang
    //
    // ValidationRequest ở đây là đúng instance mà validator đã kiểm tra, nên các giá trị đọc ra chắc chắn hợp lệ
    // (adminId là số, page/size là số nguyên hoặc không truyền, body là JSON object đúng schema)
    @PostMapping("/admins/{adminId}/users/search")
    public ResponseEntity<ApiResponse<List<UserSummaryDto>>> searchUsers(ValidationRequest request) {
        Long adminId = Long.valueOf(request.getPathVariable("adminId").trim());
        MultiValueMap<String, String> queryParams = request.getQueryParams();
        int page = intParam(queryParams, "page", DEFAULT_PAGE);
        int size = intParam(queryParams, "size", DEFAULT_SIZE);
        UserSearchRequest filter = objectMapper.convertValue(request.getBodyJson(), UserSearchRequest.class);

        Page<UserSummaryDto> result = testService.searchUsers(adminId, filter, page, size);
        return ResponseHandler.generateResponse(ApiStatus.OK,
                ApiResponse.page(result.getContent(), PageMeta.of(page, size, result.getTotalElements())));
    }

    // Query param kiểu số nguyên (lấy giá trị đầu tiên nếu truyền lặp lại), dùng giá trị mặc định khi không truyền
    private static int intParam(MultiValueMap<String, String> queryParams, String name, int defaultValue) {
        String value = queryParams.getFirst(name);
        return value == null ? defaultValue : Integer.parseInt(value.trim());
    }
}
