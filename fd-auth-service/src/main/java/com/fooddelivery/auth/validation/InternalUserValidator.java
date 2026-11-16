package com.fooddelivery.auth.validation;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.id.entity.EntityType;
import com.fooddelivery.id.entity.IdUtils;
import com.fooddelivery.web.exception.BusinessException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Validators of the APIs in {@code InternalUserController}, called by the API flow at request time, before the
 * controller.
 *
 * <p>Only checks the input itself and never queries the database; whether the user exists is checked by
 * {@code UserQueryService}.
 */
@Component
public class InternalUserValidator {
    private static final Logger log = LogManager.getLogger(InternalUserValidator.class);

    // Exactly 14 digits, the format of IdUtils, e.g. "17939313751001"
    private static final Pattern ID = Pattern.compile("^\\d{14}$");

    /**
     * Validator of {@code GET /internal/users/{userId}} ({@code <validator>} in {@code api-routes.xml}).
     *
     * <p>Throws {@code BusinessException} 400 {@code INVALID_USER_ID}, with a field error on {@code userId}, if the
     * path variable is not a 14-digit ID of a user, e.g. {@code abc}, {@code 123} or the ID of an order (the 11th digit
     * tells the type of object).
     *
     * @param request the request, with the {@code userId} path variable
     */
    public void validateGetUser(ApiRequest request) {
        String userId = request.getPathVariable("userId").asString();
        log.info("[VALIDATE] [{}] {} | userId={}", request.getMethod(), request.getPath(), userId);

        if (userId == null || !ID.matcher(userId).matches() || !isUserId(Long.parseLong(userId))) {
            throw BusinessException.ofField(ApiStatus.BAD_REQUEST, "INVALID_USER_ID", "userId",
                    "userId must be the 14-digit ID of a user");
        }
    }

    // True if the 11th digit is the code of EntityType.USER; any other digit, known or not, is another kind of ID
    private static boolean isUserId(long id) {
        try {
            return IdUtils.decodeObjectType(id) == EntityType.USER;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
