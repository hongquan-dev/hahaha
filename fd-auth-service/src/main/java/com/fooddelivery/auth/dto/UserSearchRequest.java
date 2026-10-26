package com.fooddelivery.auth.dto;

// Body của POST /auth/test/admins/{adminId}/users/search. Mọi field đều không bắt buộc, null = không lọc theo field đó.
// Cấu trúc được kiểm tra bởi schemas/user-search.json, giá trị được kiểm tra bởi TestValidator.validateUserSearch
public record UserSearchRequest(
        String role,      // so khớp chính xác, không phân biệt hoa thường, vd. "CUSTOMER"
        Boolean active,   // true / false
        String keyword    // tìm trong username hoặc email, không phân biệt hoa thường
) {
}
