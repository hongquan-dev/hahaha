package com.fooddelivery.auth.validation;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.auth.dto.CreateProductRequest;
import com.fooddelivery.auth.repository.ProductRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

// Validator cho các API trong ProductController.
// api-flow gọi nó trước controller; trả false thì request bị từ chối với 400 REQUEST_VALIDATION_FAILED
@Component
public class ProductValidator {
    private static final Logger log = LogManager.getLogger(ProductValidator.class);

    private static final Set<String> CATEGORIES = Set.of("FOOD", "DRINK", "DESSERT");
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_PRICE_SCALE = 2;
    // Khớp với cột price NUMERIC(12, 2): tối đa 10 chữ số phần nguyên
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");

    private final ProductRepository productRepository;

    public ProductValidator(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // Khớp với <validator>...ProductValidator.validateCreateProduct</validator> trong api-routes.xml.
    // Cấu trúc body (đủ field, đúng kiểu, không có field lạ) đã được schemas/create-product.json kiểm tra trước đó
    public boolean validateCreateProduct(ApiRequest request) {
//        // 1. Thông tin chung của request
//        log.info("TEST : Method              = {}", request.getMethod()); // ok
//        log.info("TEST : Path                = {}", request.getPath()); // ok
//        log.info("TEST : Full URL            = {}", request.getFullUrl()); // ok
//
//        // 2. Query param theo tên, vd. ?page=1. null nếu không truyền
        log.info("TEST : Param page = {}", request.getParam("page")); // ok
//
//        // 3. Header theo tên, không phân biệt hoa thường. null nếu không truyền
//        log.info("TEST : Header X-Client-Id  = {}", request.getHeader("X-Client-Id")); // ok
//        log.info("TEST : Header Content-Type = {}", request.getHeader("Content-Type")); // ok
//        log.info("TEST : Header Cookie = {}", request.getHeader("Cookie")); // ok
//
//        // 4. Cả body gán vào DTO. null nếu body rỗng hoặc không chuyển được sang DTO
//        CreateProductRequest body = request.getBody(CreateProductRequest.class);
//        log.info("TEST : Body (DTO)          = {}", body);
//
//        // 5. Tham số trên URL theo tên trong <path> của api-routes.xml. Route này không có nên là null
//        log.info("TEST : Path var productId  = {}", request.getPathVariable("object-id")); // ok

        return true;
    }

    // Khớp với <validator>...ProductValidator.validateGetProduct</validator> trong api-routes.xml.
    // productId lấy từ path variable, phải là số nguyên dương và tồn tại trong DB
    public boolean validateGetProduct(ApiRequest request) {
        log.info("[VALIDATE] [{}] {} | productId={}", request.getMethod(), request.getPath(),
                request.getPathVariable("productId"));

        String productIdText = request.getPathVariable("productId").asString();
        Long productId = parseLong(productIdText);
        if (productId == null || productId <= 0) {
            return reject("productId must be a positive number, got '" + productIdText + "'");
        }
        if (!productRepository.existsById(productId)) {
            return reject("product " + productId + " does not exist");
        }

        return true;
    }

    // Client chỉ nhận REQUEST_VALIDATION_FAILED, nên ghi lý do cụ thể vào log
    private static boolean reject(String reason) {
        log.warn("[VALIDATE] Rejected: {}", reason);
        return false;
    }

    // null nếu không truyền hoặc không phải số nguyên
    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
