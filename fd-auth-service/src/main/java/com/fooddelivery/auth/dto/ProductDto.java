package com.fooddelivery.auth.dto;

import com.fooddelivery.auth.common.model.Product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Thông tin sản phẩm trả ra API, không trả thẳng entity
public record ProductDto(
        Long id,
        String name,
        String category,
        BigDecimal price,
        LocalDateTime createdAt
) {
    public static ProductDto from(Product product) {
        return new ProductDto(product.getId(), product.getName(), product.getCategory(), product.getPrice(),
                product.getCreatedAt());
    }
}
