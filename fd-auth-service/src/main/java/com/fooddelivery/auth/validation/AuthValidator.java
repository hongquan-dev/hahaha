package com.fooddelivery.auth.validation;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.auth.dto.RegisterRequest;
import com.fooddelivery.web.exception.BusinessException;
import com.fooddelivery.web.model.FieldErrorDetail;
import com.fooddelivery.web.status.ApiStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validators of the APIs in {@code AuthController}, called by the API flow at request time, after the JSON Schema and
 * before the controller.
 *
 * <p>Only checks the input itself (required fields, format) and never queries the database; business rules such as
 * duplicate accounts belong to {@code AuthService}.
 *
 * <p>An invalid request is rejected by throwing {@code BusinessException} with one field error per invalid field,
 * instead of returning {@code false}: {@code false} only gives the client a generic 400
 * {@code REQUEST_VALIDATION_FAILED}, while a form needs to know which field is wrong. {@code GlobalExceptionHandler}
 * (common-web-lib) turns the exception into the error response.
 */
@Component
public class AuthValidator {
    private static final Logger log = LogManager.getLogger(AuthValidator.class);

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._]{3,30}$");
    // Catches common typos only; proving that the address exists needs a verification email
    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}$");
    private static final int MAX_EMAIL_LENGTH = 254;
    // Vietnamese mobile prefixes 03, 05, 07, 08, 09, after normalization to +84, e.g. "+84912345678"
    private static final Pattern PHONE = Pattern.compile("^\\+84[35789]\\d{8}$");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 64;
    // BCrypt ignores everything after byte 72, so a longer password would be silently truncated. A character can take
    // up to 4 bytes in UTF-8, hence this check on top of MAX_PASSWORD_LENGTH
    private static final int MAX_PASSWORD_BYTES = 72;

    /**
     * Validator of {@code POST /auth/register} ({@code <validator>} in {@code api-routes.xml}).
     *
     * <p>{@code schemas/register.json} has already checked that the body has the four string fields and nothing else.
     * Errors of every field are collected and thrown once as {@code BusinessException} 400
     * {@code INVALID_REGISTER_INFO}, so the form can show all of them together.
     *
     * @param request the request, with a JSON body
     * @return {@code true} if the body is valid; never {@code false}, an invalid body throws instead
     */
    public boolean validateRegister(ApiRequest request) {
        RegisterRequest body = request.getBody(RegisterRequest.class).normalized();
        // The password is never logged
        log.info("[VALIDATE] [{}] {} | username={}, email={}, phoneNumber={}", request.getMethod(), request.getPath(),
                body.username(), body.email(), body.phoneNumber());

        List<FieldErrorDetail> errors = new ArrayList<>();
        if (isBlank(body.username())) {
            errors.add(new FieldErrorDetail("username", "Username is required"));
        } else if (!USERNAME.matcher(body.username()).matches()) {
            errors.add(new FieldErrorDetail("username",
                    "Username must be 3-30 characters of letters, digits, '.' or '_'"));
        }

        if (isBlank(body.password())) {
            errors.add(new FieldErrorDetail("password", "Password is required"));
        } else {
            String passwordError = checkPassword(body.password());
            if (passwordError != null) {
                errors.add(new FieldErrorDetail("password", passwordError));
            }
        }

        if (isBlank(body.email())) {
            errors.add(new FieldErrorDetail("email", "Email is required"));
        } else if (body.email().length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(body.email()).matches()) {
            errors.add(new FieldErrorDetail("email", "Email is invalid"));
        }

        if (isBlank(body.phoneNumber())) {
            errors.add(new FieldErrorDetail("phoneNumber", "Phone number is required"));
        } else if (!PHONE.matcher(body.phoneNumber()).matches()) {
            errors.add(new FieldErrorDetail("phoneNumber", "Phone number must be a Vietnamese mobile number"));
        }

        if (!errors.isEmpty()) {
            throw new BusinessException(ApiStatus.BAD_REQUEST, "INVALID_REGISTER_INFO", "Registration info is invalid",
                    errors);
        }
        return true;
    }

    // The schema already rejects null ("type": "string"); checked again so the validator does not depend on it.
    // An empty or whitespace-only string passes the schema
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // Reason the password is invalid, null if it is valid. A special character is anything that is not a letter, a
    // digit or whitespace, e.g. ! @ # $ % _ -
    private static String checkPassword(String password) {
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return "Password must be " + MIN_PASSWORD_LENGTH + "-" + MAX_PASSWORD_LENGTH + " characters";
        }
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = password.chars()
                .anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
        if (!hasUpper || !hasLower || !hasDigit || !hasSpecial) {
            return "Password must contain an uppercase letter, a lowercase letter, a digit and a special character";
        }
        return null;
    }
}
