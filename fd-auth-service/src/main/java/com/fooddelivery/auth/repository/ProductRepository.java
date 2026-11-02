package com.fooddelivery.auth.repository;

import com.fooddelivery.auth.common.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    // Tên sản phẩm là duy nhất, không phân biệt hoa thường; dùng ở ProductValidator.validateCreateProduct
    boolean existsByNameIgnoreCase(String name);
}
