package com.fooddelivery.auth.service.auth;


import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.common.util.IdentifierNormalizer;
import com.fooddelivery.auth.dto.LoginRequest;
import com.fooddelivery.auth.dto.RegisterRequest;
import com.fooddelivery.auth.dto.UserDto;
import com.fooddelivery.auth.repository.UserRepository;
import com.fooddelivery.auth.service.session.IssuedTokens;
import com.fooddelivery.auth.service.session.SessionService;
import com.fooddelivery.auth.service.token.AccessTokenVerification;
import com.fooddelivery.auth.service.token.TokenService;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic of the APIs in {@code AuthController}.
 *
 * <p>Input reaching this class has already passed {@code AuthValidator} (required fields, format), so it is not checked
 * again here; rules that need the database, such as duplicate accounts, live here instead. Sessions (refresh tokens)
 * are delegated to {@link SessionService}, token signing and checking to {@link TokenService}.
 */
@Service
public class AuthService {
    private static final Logger log = LogManager.getLogger(AuthService.class);

    // Role of every account created by public registration. Other roles (restaurant, shipper, admin) are granted by
    // an admin
    public static final String DEFAULT_ROLE = "CUSTOMER";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;
    private final TokenService tokenService;

    // BCrypt hash of a random password, checked when the login identifier matches no account so that the response
    // takes as long as a wrong password (~100 ms) and does not reveal which accounts exist. Built once at startup with
    // the same encoder, hence the same cost
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, SessionService sessionService,
                       TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
        this.tokenService = tokenService;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
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

    /**
     * Checks a username/email/phone number and password and starts a new session for the account.
     *
     * <p>Throws {@code BusinessException}:
     * <ul>
     *   <li>401 {@code INVALID_CREDENTIALS} if no account matches the identifier or the password is wrong, with the
     *       same message and no field errors in both cases, so the response does not reveal which accounts exist</li>
     *   <li>403 {@code ACCOUNT_DISABLED} if the password is right but the account is disabled; only reported after
     *       the password check, so it reveals nothing to someone who does not know the password</li>
     * </ul>
     *
     * @param request   body that passed {@code AuthValidator.validateLogin}
     * @param userAgent {@code User-Agent} header, {@code null} if absent; stored with the session
     * @param ip        client IP, stored with the session
     * @return the user and the tokens of the new session
     */
    @Transactional
    public LoginResult login(LoginRequest request, String userAgent, String ip) {
        User user = findByIdentifier(request.identifier()).orElse(null);
        // Always run BCrypt, against a dummy hash when there is no account, so both failures take the same time
        boolean passwordMatches = passwordEncoder.matches(request.password(),
                user != null ? user.getPassword() : dummyPasswordHash);
        if (user == null || !passwordMatches) {
            log.warn("[LOGIN] Failed login for '{}' from {}", request.identifier().trim(), ip);
            throw new BusinessException(ApiStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "Username or password is incorrect");
        }
        if (!user.isActive()) {
            log.warn("[LOGIN] Login of disabled user {} from {}", user.getId(), ip);
            throw new BusinessException(ApiStatus.FORBIDDEN, "ACCOUNT_DISABLED", "Account is disabled");
        }

        IssuedTokens tokens = sessionService.start(user, userAgent, ip);
        log.info("[LOGIN] User {} '{}' logged in from {}", user.getId(), user.getUsername(), ip);
        return new LoginResult(UserDto.from(user), tokens);
    }

    /**
     * User of an access token, for {@code GET /auth/me}.
     *
     * <p>Throws {@code BusinessException} 401 {@code TOKEN_EXPIRED} if the token is genuine but expired (the client
     * should refresh), or 401 {@code UNAUTHENTICATED} if it is missing or invalid, or its user no longer exists or is
     * disabled.
     *
     * @param accessToken value of the {@code access_token} cookie, {@code null} if absent
     * @return the user, read from the database so the data is current
     */
    @Transactional(readOnly = true)
    public UserDto currentUser(String accessToken) {
        AccessTokenVerification verification = tokenService.verifyAccessToken(accessToken);
        if (verification.status() == AccessTokenVerification.Status.EXPIRED) {
            throw new BusinessException(ApiStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Access token has expired");
        }
        if (!verification.isValid()) {
            throw unauthenticated();
        }
        return userRepository.findById(verification.userId())
                .filter(User::isActive)
                .map(UserDto::from)
                .orElseThrow(AuthService::unauthenticated);
    }

    // Account matching what the user typed: an email if it contains "@"; a phone number if it normalizes to one,
    // falling back to a username because a username may be all digits (e.g. "0912345678"); a username otherwise
    private Optional<User> findByIdentifier(String identifier) {
        String trimmed = identifier.trim();
        if (trimmed.contains("@")) {
            return userRepository.findByEmail(IdentifierNormalizer.lowerTrim(trimmed));
        }
        String phone = IdentifierNormalizer.normalizePhone(trimmed);
        if (IdentifierNormalizer.isPhone(phone)) {
            Optional<User> byPhone = userRepository.findFirstByPhoneNumber(phone);
            if (byPhone.isPresent()) {
                return byPhone;
            }
        }
        return userRepository.findByUsername(IdentifierNormalizer.lowerTrim(trimmed));
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(ApiStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Login required");
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
