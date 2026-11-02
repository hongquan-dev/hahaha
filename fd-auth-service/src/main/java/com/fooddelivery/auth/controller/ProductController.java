package com.fooddelivery.auth.controller;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.auth.dto.ProductDto;
import com.fooddelivery.auth.service.ProductService;
import com.fooddelivery.web.handler.ResponseHandler;
import com.fooddelivery.web.model.ApiResponse;
import com.fooddelivery.web.status.ApiStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// ApiRequest ở đây là đúng instance mà validator đã kiểm tra, nên các giá trị đọc ra chắc chắn hợp lệ
@RestController
@RequestMapping("/auth/products")
public class ProductController {
    private static final Logger log = LogManager.getLogger(ProductController.class);

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    // API không có tham số trên URL.
    // Luồng: schema (schemas/create-product.json) -> validator (ProductValidator.validateCreateProduct, có truy vấn DB)
    // -> controller -> service -> repository.
    // POST /auth/products, body {"name", "category", "price"} -> 201, sản phẩm vừa tạo
    @PostMapping("/{object-id}")
    // Không cần try/catch: checkPage ném BusinessException khi page sai, GlobalExceptionHandler (common-web-lib)
    // bắt và trả 400 kèm code/field/message; lỗi khác cũng được nó trả 500
    public ResponseEntity<ApiResponse<Integer>> createProduct(ApiRequest request) {
        Integer page = productService.checkPage(request.getParam("page").asInt());
        return ResponseHandler.generateResponse(ApiStatus.CREATED, ApiResponse.success(page));
    }

    // API có tham số trên URL (path variable productId), không có body.
    // Luồng: validator (ProductValidator.validateGetProduct, có truy vấn DB) -> controller -> service -> repository.
    // GET /auth/products/{productId} -> 200, chi tiết sản phẩm
    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductDto>> getProduct(ApiRequest request) {
        Long productId = Long.valueOf(request.getPathVariable("productId").asString().trim());
        return ResponseHandler.generateResponse(ApiStatus.OK, ApiResponse.success(productService.getProduct(productId)));
    }
}
