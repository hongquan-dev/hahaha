package com.fooddelivery.auth.service.auth;


import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.common.util.IdentifierNormalizer;
import com.fooddelivery.auth.common.util.LogMasker;
import com.fooddelivery.auth.dto.LoginRequest;
import com.fooddelivery.auth.dto.RegisterRequest;
import com.fooddelivery.auth.dto.UserDto;
import com.fooddelivery.auth.repository.UserRepository;
import com.fooddelivery.auth.service.session.IssuedTokens;
import com.fooddelivery.auth.service.session.SessionService;
import com.fooddelivery.auth.service.token.AccessTokenVerification;
import com.fooddelivery.auth.service.token.TokenService;
import com.fooddelivery.dto.auth.UserRole;
import com.fooddelivery.id.entity.EntityType;
import com.fooddelivery.id.entity.IdUtils;
import com.fooddelivery.web.exception.BusinessException;
import com.fooddelivery.dto.response.FieldErrorDetail;
import com.fooddelivery.dto.response.ApiStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic of the APIs in {@code AuthController}.
 *
 * <p>Input reaching this class has already passed {@code AuthValidator} (required fields, format), so it is not checked
 * again here; rules that need the database, such as duplicate accounts, live here instead. Sessions (refresh tokens)
 * are delegated to {@link SessionService}, token signing and checking to {@link TokenService}, the lockout after failed
 * logins to {@link LoginThrottleService}.
 */
@Service
public class AuthService {
    private static final Logger log = LogManager.getLogger(AuthService.class);

    // Role of every account created by public registration. Other roles (restaurant owner, driver, admin) are granted
    // by an admin
    public static final UserRole DEFAULT_ROLE = UserRole.CUSTOMER;

    private static final FieldErrorDetail USERNAME_TAKEN = new FieldErrorDetail("username", "Username is already taken");
    private static final FieldErrorDetail EMAIL_TAKEN = new FieldErrorDetail("email", "Email is already registered");
    private static final FieldErrorDetail PHONE_TAKEN =
            new FieldErrorDetail("phoneNumber", "Phone number is already registered");

    // Unique constraints of the users table (migrations V1, V2) -> field error of a duplicate on it, so a registration
    // race gives the same field error as the duplicate check
    private static final Map<String, FieldErrorDetail> DUPLICATE_BY_CONSTRAINT = Map.of(
            "uk_users_username", USERNAME_TAKEN,
            "uk_users_email", EMAIL_TAKEN,
            "uk_users_phone_number", PHONE_TAKEN);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;
    private final TokenService tokenService;
    private final LoginThrottleService loginThrottle;

    // BCrypt hash of a random password, checked when the login identifier matches no account so that the response
    // takes as long as a wrong password (~100 ms) and does not reveal which accounts exist. Built once at startup with
    // the same encoder, hence the same cost
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, SessionService sessionService,
                       TokenService tokenService, LoginThrottleService loginThrottle) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
        this.tokenService = tokenService;
        this.loginThrottle = loginThrottle;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Creates a {@code CUSTOMER} account with the password hashed by BCrypt.
     *
     * <p>Throws {@code BusinessException} 409 {@code USER_ALREADY_EXISTS} if the username, email or phone number is
     * already registered, with one field error per duplicate field, also when another registration saves the same
     * value between the duplicate check and the save. Any other database error is thrown as it is.
     *
     * <p>The password is hashed before the database is touched, and the method opens no transaction of its own: the
     * duplicate checks and the save each run in the repository's transaction. So BCrypt (~100 ms) holds no pooled
     * connection, and the unique constraints catch whatever happens between the check and the save.
     *
     * @param request body that passed {@code AuthValidator.validateRegister}, not normalized yet
     * @return the created user
     */
    public UserDto register(RegisterRequest request) {
        RegisterRequest body = request.normalized();
        String passwordHash = passwordEncoder.encode(body.password());
        checkNotRegistered(body);

        User user = new User(IdUtils.generate(EntityType.USER), body.username(), passwordHash, body.email(),
                body.phoneNumber(), DEFAULT_ROLE);

        try {
            // Flushed now so a unique constraint violation is thrown here, inside the try, even if a caller wraps
            // this method in a transaction that would otherwise commit later
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // checkNotRegistered already looked for duplicates. This only happens when two requests with the same
            // username, email or phone number both pass that check before either is saved
            String constraint = uniqueConstraintName(e);
            FieldErrorDetail duplicate = constraint == null ? null : DUPLICATE_BY_CONSTRAINT.get(constraint);
            if (duplicate == null) {
                throw e;
            }
            throw new BusinessException(ApiStatus.CONFLICT, "USER_ALREADY_EXISTS", "Account already exists",
                    List.of(duplicate));
        }

        log.info("Registered user {} '{}'", user.getId(), user.getUsername());
        return UserDto.from(user);
    }

    // Name of the unique constraint the database rejected, lowercased, e.g. "uk_users_email"; null if the error is not
    // a unique constraint violation (NOT NULL, column length, primary key...) or the name is unknown
    private static String uniqueConstraintName(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE
                    && violation.getConstraintName() != null) {
                return violation.getConstraintName().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * Checks a username/email/phone number and password and starts a new session for the account.
     *
     * <p>Throws {@code BusinessException}:
     * <ul>
     *   <li>429 {@code TOO_MANY_LOGIN_ATTEMPTS} if the identity is locked by {@link LoginThrottleService} after too
     *       many failures, before the password is checked; the same for an existing account and an unknown
     *       identifier</li>
     *   <li>401 {@code INVALID_CREDENTIALS} if no account matches the identifier or the password is wrong, with the
     *       same message and no field errors in both cases, so the response does not reveal which accounts exist.
     *       Counted as a failure by {@link LoginThrottleService}</li>
     *   <li>403 {@code ACCOUNT_DISABLED} if the password is right but the account is disabled; only reported after
     *       the password check, so it reveals nothing to someone who does not know the password</li>
     * </ul>
     * A right password clears the failures of the identity, also for a disabled account.
     *
     * <p>Opens no transaction of its own: the account lookup runs in the repository's transaction and the new session
     * in {@link SessionService#start}'s, so BCrypt (~100 ms) between them holds no pooled connection. This relies on
     * {@code spring.jpa.open-in-view=false}: with it on, the connection of the lookup stays held until the response.
     *
     * @param request   body that passed {@code AuthValidator.validateLogin}
     * @param userAgent {@code User-Agent} header, {@code null} if absent; stored with the session
     * @param ip        client IP, stored with the session
     * @return the user and the tokens of the new session
     */
    public LoginResult login(LoginRequest request, String userAgent, String ip) {
        User user = findByIdentifier(request.identifier()).orElse(null);
        String throttleKey = loginThrottle.keyFor(user != null ? user.getId() : null, request.identifier());
        loginThrottle.checkNotLocked(throttleKey);

        // Always run BCrypt, against a dummy hash when there is no account, so both failures take the same time
        boolean passwordMatches = passwordEncoder.matches(request.password(),
                user != null ? user.getPassword() : dummyPasswordHash);
        if (user == null || !passwordMatches) {
            loginThrottle.recordFailure(throttleKey);
            // The identifier is masked, since it may be a password typed in the wrong field. The user ID is not
            // secret and lets repeated failures on one account be followed in the log
            if (user == null) {
                log.warn("[LOGIN] Failed login for '{}' from {}: no such account",
                        LogMasker.identifier(request.identifier()), ip);
            } else {
                log.warn("[LOGIN] Failed login for user {} ('{}') from {}: wrong password", user.getId(),
                        LogMasker.identifier(request.identifier()), ip);
            }
            throw new BusinessException(ApiStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "Username or password is incorrect");
        }
        loginThrottle.reset(throttleKey);
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
        AccessTokenVerification verification = authenticate(accessToken);
        return userRepository.findById(verification.userId())
                .filter(User::isActive)
                .map(UserDto::from)
                .orElseThrow(AuthService::unauthenticated);
    }

    /**
     * Checks the access token of a request that needs a logged-in user, from its signature and claims only.
     *
     * <p>Throws {@code BusinessException} 401 {@code TOKEN_EXPIRED} if the token is genuine but expired (the client
     * should refresh), or 401 {@code UNAUTHENTICATED} if it is missing or invalid. The database is not read, so a token
     * of a user disabled since it was issued stays valid until it expires.
     *
     * @param accessToken value of the {@code access_token} cookie, {@code null} if absent
     * @return the verification, always {@code VALID}
     */
    public AccessTokenVerification authenticate(String accessToken) {
        AccessTokenVerification verification = tokenService.verifyAccessToken(accessToken);
        if (verification.status() == AccessTokenVerification.Status.EXPIRED) {
            throw new BusinessException(ApiStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Access token has expired");
        }
        if (!verification.isValid()) {
            throw unauthenticated();
        }
        return verification;
    }

    // Account matching what the user typed: an email if it contains "@"; a phone number if it normalizes to one,
    // falling back to a username for accounts registered before usernames had to start with a letter (e.g.
    // "0912345678"); a username otherwise
    private Optional<User> findByIdentifier(String identifier) {
        String trimmed = IdentifierNormalizer.strip(identifier);
        if (trimmed.contains("@")) {
            return userRepository.findByEmail(IdentifierNormalizer.lowerTrim(trimmed));
        }
        String phone = IdentifierNormalizer.normalizePhone(trimmed);
        if (IdentifierNormalizer.isPhone(phone)) {
            Optional<User> byPhone = userRepository.findByPhoneNumber(phone);
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
            errors.add(USERNAME_TAKEN);
        }
        if (userRepository.existsByEmail(body.email())) {
            errors.add(EMAIL_TAKEN);
        }
        if (userRepository.existsByPhoneNumber(body.phoneNumber())) {
            errors.add(PHONE_TAKEN);
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ApiStatus.CONFLICT, "USER_ALREADY_EXISTS", "Account already exists", errors);
        }
    }
}
