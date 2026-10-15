package com.fooddelivery.auth.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

@Component
public class AuthValidator {
    private static final Logger log = LogManager.getLogger(AuthValidator.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> MOCK_TAKEN_USERNAMES = Set.of("admin", "root", "test");

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.]+$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^0[0-9]{9}$");

    // Hàm validate này khớp với tên <validator>...AuthValidator.validateLogin</validator> trong routes.xml
    // Cấu trúc body (bắt buộc, kiểu dữ liệu) đã được kiểm tra qua schemas/login.json,
    // ở đây kiểm tra giá trị (độ dài, định dạng) và các rule nghiệp vụ.
    public boolean validateLogin(HttpServletRequest request, String requestBody) {
        log.info("[1. VALIDATE] Đang chạy hàm validateLogin...");

        JsonNode body = parseBody(requestBody);
        if (body == null) {
            return false;
        }

        String username = body.path("username").asText();
        String password = body.path("password").asText();

        if (username.isBlank() || !lengthBetween(username, 3, 50)) {
            log.warn("[1. VALIDATE] Username phải dài 3-50 ký tự và không được để trống");
            return false;
        }
        if (!lengthBetween(password, 1, 100)) {
            log.warn("[1. VALIDATE] Password phải dài 1-100 ký tự");
            return false;
        }

        log.info("[1. VALIDATE] Thành công! Dữ liệu hợp lệ.");
        return true;
    }

    // Khớp với <validator>...AuthValidator.validateRegister</validator> trong routes.xml
    // Cấu trúc body đã được kiểm tra qua schemas/register.json
    public boolean validateRegister(HttpServletRequest request, String requestBody) {
        log.info("[1. VALIDATE] Đang chạy hàm validateRegister...");

        JsonNode body = parseBody(requestBody);
        if (body == null) {
            return false;
        }

        String username = body.path("username").asText();
        String password = body.path("password").asText();
        String email = body.path("email").asText();
        String phoneNumber = body.path("phoneNumber").asText();

        // Kiểm tra độ dài, định dạng
        if (!lengthBetween(username, 3, 50) || !USERNAME_PATTERN.matcher(username).matches()) {
            log.warn("[1. VALIDATE] Username phải dài 3-50 ký tự, chỉ gồm chữ, số, '_' và '.'");
            return false;
        }
        if (!lengthBetween(password, 6, 100)) {
            log.warn("[1. VALIDATE] Password phải dài 6-100 ký tự");
            return false;
        }
        if (!lengthBetween(email, 1, 100) || !EMAIL_PATTERN.matcher(email).matches()) {
            log.warn("[1. VALIDATE] Email không đúng định dạng hoặc dài quá 100 ký tự");
            return false;
        }
        if (!PHONE_PATTERN.matcher(phoneNumber).matches()) {
            log.warn("[1. VALIDATE] Số điện thoại phải gồm 10 số và bắt đầu bằng 0");
            return false;
        }

        // Dữ liệu giả: danh sách username đã bị chiếm (sau này thay bằng tra cứu DB)
        if (MOCK_TAKEN_USERNAMES.contains(username.toLowerCase())) {
            log.warn("[1. VALIDATE] Username đã tồn tại: {}", username);
            return false;
        }

        // Không cho đặt mật khẩu trùng với username
        if (password.equalsIgnoreCase(username)) {
            log.warn("[1. VALIDATE] Mật khẩu không được trùng với username");
            return false;
        }

        log.info("[1. VALIDATE] Thành công! Dữ liệu hợp lệ.");
        return true;
    }

    private JsonNode parseBody(String requestBody) {
        try {
            return objectMapper.readTree(requestBody);
        } catch (JsonProcessingException e) {
            log.warn("[1. VALIDATE] Không đọc được body: {}", e.getOriginalMessage());
            return null;
        }
    }

    // Đếm theo code point để emoji / ký tự đặc biệt chỉ tính là 1 ký tự
    private boolean lengthBetween(String value, int min, int max) {
        int length = value.codePointCount(0, value.length());
        return length >= min && length <= max;
    }
}
