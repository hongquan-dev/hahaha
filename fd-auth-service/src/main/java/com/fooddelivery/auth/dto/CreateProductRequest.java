package com.fooddelivery.auth.dto;

import java.math.BigDecimal;

// Body của POST /auth/products/{object-id}, lấy bằng request.getBody(CreateProductRequest.class).
// Cấu trúc được kiểm tra bởi schemas/create-product.json, giá trị được kiểm tra bởi ProductValidator.validateCreateProduct
public record CreateProductRequest(
        String name,        // 1-100 ký tự, không trùng tên sản phẩm đã có (không phân biệt hoa thường)
        String category,    // FOOD / DRINK / DESSERT, không phân biệt hoa thường
        BigDecimal price,   // > 0, tối đa 2 chữ số thập phân
        BigDecimal test     // field thử nghiệm, không bắt buộc, null nếu không truyền
) {
}
