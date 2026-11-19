package com.fooddelivery.auth.validation;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.id.entity.EntityType;
import com.fooddelivery.id.entity.IdUtils;
import com.fooddelivery.web.exception.BusinessException;

/**
 * Check of the user ID that another service asks auth-service about, called at request time by
 * {@code UserGrpcService} before it queries the database.
 *
 * <p>Only checks the ID itself and never queries the database; whether the user exists is checked by
 * {@code UserQueryService}. gRPC has no API flow, so this is a plain static method rather than a {@code <validator>}
 * of {@code api-routes.xml}.
 */
public final class InternalUserValidator {

    private InternalUserValidator() {
    }

    /**
     * Checks that {@code userId} is the ID of a user, e.g. the {@code user_id} of the gRPC {@code GetUserRequest}.
     *
     * <p>Throws {@code BusinessException} 400 {@code INVALID_USER_ID}, with a field error on {@code userId}, if the ID
     * is not 14 digits or its 11th digit is not the code of {@code EntityType.USER}, e.g. {@code 0} or the ID of an
     * order.
     *
     * @param userId the ID to check
     */
    public static void checkUserId(long userId) {
        if (!isUserId(userId)) {
            throw invalidUserId();
        }
    }

    private static BusinessException invalidUserId() {
        return BusinessException.ofField(ApiStatus.BAD_REQUEST, "INVALID_USER_ID", "userId",
                "userId must be the 14-digit ID of a user");
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
