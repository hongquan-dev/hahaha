package com.fooddelivery.restaurant.validation;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.dto.response.FieldErrorDetail;
import com.fooddelivery.restaurant.dto.CreateRestaurantRequest;
import com.fooddelivery.web.exception.BusinessException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validators of the APIs in {@code RestaurantController}, called by the API flow at request time, after the role check
 * and the JSON Schema, before the controller.
 *
 * <p>Only checks the input itself (required fields, length, format) and never queries the database; business rules
 * such as one restaurant per owner belong to {@code RestaurantService}. An invalid request is rejected by throwing
 * {@code BusinessException} with one field error per invalid field, so the form knows which field is wrong.
 */
@Component
public class RestaurantValidator {
    private static final Logger log = LogManager.getLogger(RestaurantValidator.class);

    // Vietnamese mobile prefixes 03, 05, 07, 08, 09, after normalization to +84, e.g. "+84912345678"
    private static final Pattern PHONE = Pattern.compile("^\\+84[35789]\\d{8}$");
    private static final int MIN_NAME_LENGTH = 2;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MIN_ADDRESS_LENGTH = 5;
    private static final int MAX_ADDRESS_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;

    /**
     * Validator of {@code POST /restaurant/restaurants} ({@code <validator>} in {@code api-routes.xml}).
     *
     * <p>{@code schemas/create-restaurant.json} has already checked the field types and that no other field is sent.
     * Values are checked after {@link CreateRestaurantRequest#normalized()}, the form the service stores. Errors of
     * every field are thrown once as {@code BusinessException} 400 {@code INVALID_RESTAURANT_INFO}.
     *
     * @param request the request, with a JSON body
     */
    public void validateCreate(ApiRequest request) {
        CreateRestaurantRequest body = request.getBody(CreateRestaurantRequest.class).normalized();
        log.info("[VALIDATE] [{}] {} | userId={}, name={}", request.getMethod(), request.getPath(),
                request.getUserId(), body.name());

        List<FieldErrorDetail> errors = new ArrayList<>();
        if (isBlank(body.name())) {
            errors.add(new FieldErrorDetail("name", "Name is required"));
        } else if (body.name().length() < MIN_NAME_LENGTH || body.name().length() > MAX_NAME_LENGTH) {
            errors.add(new FieldErrorDetail("name",
                    "Name must be " + MIN_NAME_LENGTH + "-" + MAX_NAME_LENGTH + " characters"));
        }

        if (isBlank(body.address())) {
            errors.add(new FieldErrorDetail("address", "Address is required"));
        } else if (body.address().length() < MIN_ADDRESS_LENGTH || body.address().length() > MAX_ADDRESS_LENGTH) {
            errors.add(new FieldErrorDetail("address",
                    "Address must be " + MIN_ADDRESS_LENGTH + "-" + MAX_ADDRESS_LENGTH + " characters"));
        }

        if (isBlank(body.phoneNumber())) {
            errors.add(new FieldErrorDetail("phoneNumber", "Phone number is required"));
        } else if (!PHONE.matcher(body.phoneNumber()).matches()) {
            errors.add(new FieldErrorDetail("phoneNumber", "Phone number must be a Vietnamese mobile number"));
        }

        if (body.description() != null && body.description().length() > MAX_DESCRIPTION_LENGTH) {
            errors.add(new FieldErrorDetail("description",
                    "Description must be at most " + MAX_DESCRIPTION_LENGTH + " characters"));
        }

        if (!errors.isEmpty()) {
            throw new BusinessException(ApiStatus.BAD_REQUEST, "INVALID_RESTAURANT_INFO",
                    "Restaurant info is invalid", errors);
        }
    }

    // The schema already rejects null for required fields; checked again so the validator does not depend on it
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
