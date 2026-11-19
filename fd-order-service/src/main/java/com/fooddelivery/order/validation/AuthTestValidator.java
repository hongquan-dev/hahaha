package com.fooddelivery.order.validation;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.web.exception.BusinessException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Validators of the APIs in {@code AuthTestController}, called by the API flow at request time, before the controller.
 *
 * <p>Only checks the input itself and never queries a database or another service.
 */
@Component
public class AuthTestValidator {
    private static final Logger log = LogManager.getLogger(AuthTestValidator.class);

    // Set by the gateway from the "sub" claim of the access token, e.g. "17939313751001"
    public static final String USER_ID_HEADER = "X-User-Id";

    // Exactly 14 digits, the format of IdUtils
    private static final Pattern ID = Pattern.compile("^\\d{14}$");

    /**
     * Validator of {@code GET /order/test/auth-grpc}.
     *
     * <p>Throws {@code BusinessException} 401 {@code UNAUTHENTICATED} if {@link #USER_ID_HEADER} is missing or not a
     * 14-digit ID. The gateway always sets it after checking the token, so this only happens when order-service is
     * called directly, bypassing the gateway.
     *
     * @param request the request, with the {@code X-User-Id} header
     */
    public void validateCaller(ApiRequest request) {
        String userId = request.getHeader(USER_ID_HEADER).asString();
        log.info("[VALIDATE] [{}] {} | userId={}", request.getMethod(), request.getPath(), userId);

        if (userId == null || !ID.matcher(userId).matches()) {
            throw new BusinessException(ApiStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Login required");
        }
    }
}
