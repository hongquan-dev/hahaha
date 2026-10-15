package com.fooddelivery.auth.controller;

import com.fooddelivery.auth.common.dto.LoginRequest;
import com.fooddelivery.auth.common.dto.LoginResponse;
import com.fooddelivery.auth.common.dto.RegisterRequest;
import com.fooddelivery.auth.common.dto.RegisterResponse;
import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.repository.UserRepository;
import com.fooddelivery.id.EntityType;
import com.fooddelivery.id.IdUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private static final Logger log = LogManager.getLogger(AuthController.class);
    private static final String DEFAULT_ROLE = "CUSTOMER";

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        log.info("Nhận request đăng ký cho tài khoản: {}", request.getUsername());

        // ID 14 số do lập trình viên tự sinh qua common-id-lib
        Long userId = Long.parseLong(IdUtils.generate(EntityType.USER));

        User user = new User(
                userId,
                request.getUsername(),
                request.getPassword(), // Tạm thời lưu mật khẩu thô (sau này nên dùng PasswordEncoder)
                request.getEmail(),
                request.getPhoneNumber(),
                DEFAULT_ROLE
        );

        // Dữ liệu giả: chưa lưu xuống DB, sau này thay bằng userRepository.save(user)
        log.info("Đăng ký thành công (mock) cho tài khoản: {} | ID: {}", user.getUsername(), user.getId());

        RegisterResponse response = new RegisterResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.getRole(),
                "Đăng ký thành công!"
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }


    @GetMapping("/health")
    public String healthCheck() {
        // 1. Thử sinh một ID mẫu cho loại đơn hàng (ORDER)
        EntityType sampleType = EntityType.ORDER;
        String sampleId = IdUtils.generate(sampleType);

        // 2. Thử giải mã ngược lại từ ID vừa sinh để kiểm tra tính toàn vẹn
        EntityType decodedType = IdUtils.decodeObjectType(sampleId);
        long epochSeconds = IdUtils.decodeTimestamp(sampleId);
        LocalDateTime createdTime = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(epochSeconds),
                ZoneId.systemDefault()
        );

        // 3. Trả về kết quả kiểm tra trạng thái kèm test lib-id
        return String.format(
                "🚀 Service is UP & Healthy! | Library Test [common-id-lib] -> " +
                        "Generated ID: %s | Entity: %s (%s) | Created At: %s",
                sampleId,
                decodedType.getDisplayName(),
                decodedType.getVietnameseName(),
                createdTime
        );
    }
}