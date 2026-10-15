package com.fooddelivery.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.auth.config.ApiRegistry;
import com.fooddelivery.auth.util.JsonSchemaUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class ApiFlowInterceptor implements HandlerInterceptor {
    private static final Logger log = LogManager.getLogger(ApiFlowInterceptor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ApiRegistry apiRegistry;

    @Autowired
    private ApplicationContext applicationContext;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String method = request.getMethod();

        ApiRegistry.RouteDefinition route = apiRegistry.getRoute(method, path);

        if (route == null) {
            log.warn("API not declared in routes config: [{}] {}", method, path);
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "API not found in system configuration!");
            return false;
        }

        if (!(handler instanceof HandlerMethod)) {
            log.warn("No controller method mapped for declared API: [{}] {}", method, path);
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "API not found in controller!");
            return false;
        }

        String validatorConfig = route.getValidator();
        boolean hasValidator = validatorConfig != null && !validatorConfig.trim().isEmpty();

        String requestBody = "";
        if (route.getSchema() != null || hasValidator) {
            requestBody = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }

        // 2. If the API has a schema configured, validate the body structure against the JSON Schema file
        if (route.getSchema() != null) {
            log.info("-> Schema Target     : {}", route.getSchemaPath());

            if (!JsonSchemaUtil.validate(route.getSchema(), requestBody)) {
                log.warn("X [SCHEMA FAILED] Request body does not match schema: {}", route.getSchemaPath());
                writeJson(response, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "Invalid request body!"));
                return false; // Stop here, do not proceed to Validator/Controller
            }
            log.info("-> [SCHEMA PASSED] Request body structure is valid.");
        } else {
            log.info("-> Schema Target     : Not required (Skipped)");
        }

        // 3. Nếu API có cấu hình validator, tiến hành tách Class và Method để gọi động
        if (hasValidator) {
            int lastDotIndex = validatorConfig.lastIndexOf('.');
            if (lastDotIndex == -1) {
                log.error("Cấu hình validator không hợp lệ (thiếu dấu chấm phân cách): {}", validatorConfig);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Lỗi cấu hình hệ thống!");
                return false;
            }

            String className = validatorConfig.substring(0, lastDotIndex);
            String methodName = validatorConfig.substring(lastDotIndex + 1);

            log.info("-> Validator Target  : {}.{}", className, methodName);

            // Dùng Spring ApplicationContext để lấy Bean động theo tên Class và gọi hàm bằng Reflection
            Class<?> clazz = Class.forName(className);
            Object validatorBean = applicationContext.getBean(clazz);

            Method validateMethod = validatorBean.getClass().getMethod(methodName, HttpServletRequest.class, String.class);
            boolean isValid = (boolean) validateMethod.invoke(validatorBean, request, requestBody);

            if (!isValid) {
                log.warn("X [VALIDATION FAILED] Chặn request tại cửa ngõ!");
                writeJson(response, HttpServletResponse.SC_BAD_REQUEST,
                        Map.of("error", "Dữ liệu đầu vào không hợp lệ qua lớp Validate!"));
                return false; // Dừng lại, không cho xuống Controller
            }
        } else {
            log.info("-> Validator Target  : Không yêu cầu (Bỏ qua)");
        }

        log.info("-> [VALIDATION PASSED] Cho phép chuyển tiếp xuống Controller.");
        log.info("==================================================");
        return true;
    }

    private void writeJson(HttpServletResponse response, int status, Object body) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(status);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}