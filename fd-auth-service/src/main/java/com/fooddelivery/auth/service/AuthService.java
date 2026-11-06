package com.fooddelivery.auth.service;

import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.dto.RegisterRequest;
import com.fooddelivery.auth.dto.UserDto;
import com.fooddelivery.auth.repository.UserRepository;
import com.fooddelivery.id.entity.EntityType;
import com.fooddelivery.id.entity.IdUtils;
import com.fooddelivery.web.exception.BusinessException;
import com.fooddelivery.web.model.FieldErrorDetail;
import com.fooddelivery.web.status.ApiStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Business logic of the APIs in {@code AuthController}.
 *
 * <p>Input reaching this class has already passed {@code AuthValidator} (required fields, format), so it is not checked
 * again here; rules that need the database, such as duplicate accounts, live here instead.
 */
@Service
public class AuthService {
    private static final Logger log = LogManager.getLogger(AuthService.class);

    // Role of every account created by public registration. Other roles (restaurant, shipper, admin) are granted by
    // an admin
    public static final String DEFAULT_ROLE = "CUSTOMER";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates a {@code CUSTOMER} account with the password hashed by BCrypt.
     *
     * <p>Throws {@code BusinessException} 409 {@code USER_ALREADY_EXISTS} if the username, email or phone number is
     * already registered, with one field error per duplicate field.
     *
     * @param request body that passed {@code AuthValidator.validateRegister}, not normalized yet
     * @return the created user
     */
    @Transactional
    public UserDto register(RegisterRequest request) {
        RegisterRequest body = request.normalized();
        checkNotRegistered(body);

        User user = new User(IdUtils.generate(EntityType.USER), body.username(),
                passwordEncoder.encode(body.password()), body.email(), body.phoneNumber(), DEFAULT_ROLE);

        try {
            // Flushed now so a unique constraint violation is thrown here, not at commit outside the try
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // checkNotRegistered already looked for duplicates. This only happens when two requests with the same
            // username or email both pass that check before either is saved
            throw new BusinessException(ApiStatus.CONFLICT, "USER_ALREADY_EXISTS", "Account already exists");
        }

        log.info("Registered user {} '{}'", user.getId(), user.getUsername());
        return UserDto.from(user);
    }

    // Collects every duplicate field and throws once, so the form can show all of them together
    private void checkNotRegistered(RegisterRequest body) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (userRepository.existsByUsername(body.username())) {
            errors.add(new FieldErrorDetail("username", "Username is already taken"));
        }
        if (userRepository.existsByEmail(body.email())) {
            errors.add(new FieldErrorDetail("email", "Email is already registered"));
        }
        if (userRepository.existsByPhoneNumber(body.phoneNumber())) {
            errors.add(new FieldErrorDetail("phoneNumber", "Phone number is already registered"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ApiStatus.CONFLICT, "USER_ALREADY_EXISTS", "Account already exists", errors);
        }
    }
}
