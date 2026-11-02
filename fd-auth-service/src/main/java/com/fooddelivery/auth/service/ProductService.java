package com.fooddelivery.auth.service;

import com.fooddelivery.auth.common.model.Product;
import com.fooddelivery.auth.dto.CreateProductRequest;
import com.fooddelivery.auth.dto.ProductDto;
import com.fooddelivery.auth.repository.ProductRepository;
import com.fooddelivery.web.exception.BusinessException;
import com.fooddelivery.web.status.ApiStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

// Nghiệp vụ của các API trong ProductController.
// Dữ liệu đầu vào đã được ProductValidator kiểm tra trước, nên ở đây không kiểm tra lại
@Service
public class ProductService {
    private static final Logger log = LogManager.getLogger(ProductService.class);

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // Tạo sản phẩm mới; name được trim, category được chuẩn hoá về chữ hoa
    @Transactional
    public ProductDto createProduct(CreateProductRequest request) {
        Product product = productRepository.save(new Product(
                request.name().trim(), request.category().trim().toUpperCase(), request.price()));
        log.info("Created product {} '{}'", product.getId(), product.getName());
        return ProductDto.from(product);
    }

    // TEST: cố tình gây lỗi hệ thống (không phải lỗi nghiệp vụ) để xem GlobalExceptionHandler xử lý thế nào.
    // page = 1 -> ArithmeticException (chia cho 0); không truyền page -> NullPointerException (so sánh null với 1)
    public Integer checkPage(Integer page) {
        if (page == 1) {
            int crash = page / 0;
        }
        return page;
    }

    // Validator đã kiểm tra sản phẩm tồn tại; chỉ còn lỗi nếu bị xoá giữa lúc validate và lúc gọi service
    @Transactional(readOnly = true)
    public ProductDto getProduct(Long productId) {
        return productRepository.findById(productId)
                .map(ProductDto::from)
                .orElseThrow(() -> new NoSuchElementException("Product " + productId + " not found"));
    }
}
