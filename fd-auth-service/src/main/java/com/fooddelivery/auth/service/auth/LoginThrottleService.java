package com.fooddelivery.auth.service.auth;

import com.fooddelivery.auth.common.model.LoginFailure;
import com.fooddelivery.auth.common.util.IdentifierNormalizer;
import com.fooddelivery.auth.config.properties.LoginThrottleProperties;
import com.fooddelivery.auth.repository.LoginFailureRepository;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.web.exception.BusinessException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Locks out password guessing: after {@code auth.login.max-failures} failed logins within
 * {@code auth.login.failure-window}, logins with the same identity are refused for {@code auth.login.lock-duration},
 * even with the right password. Called by {@link AuthService#login} at request time; the failures live in the
 * {@code login_failures} table, so every auth-service instance sees the same counts.
 *
 * <p>The identity is the account when the identifier matches one, so a username, email and phone number of one
 * account share a count, and otherwise the identifier as typed. Both are locked the same way with the same answer, so
 * a lock reveals nothing about which accounts exist. The lock is short on purpose: anyone can lock an account by
 * failing on purpose, so it slows guessing down without locking the owner out for long. Spreading guesses over many
 * IPs is what this adds to the per-IP limit of the gateway.
 */
@Service
public class LoginThrottleService {
    private static final Logger log = LogManager.getLogger(LoginThrottleService.class);

    private final LoginFailureRepository loginFailureRepository;
    private final LoginThrottleProperties properties;
    private final Clock clock;

    public LoginThrottleService(LoginFailureRepository loginFailureRepository, LoginThrottleProperties properties,
                                Clock clock) {
        this.loginFailureRepository = loginFailureRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Key of the identity a login is throttled under: the account if one matched, otherwise the identifier as typed,
     * normalized like a registration so {@code " Quan "} and {@code "quan"} share a key.
     *
     * @param userId     ID of the account the identifier matched, {@code null} if none
     * @param identifier identifier as typed, used only when {@code userId} is {@code null}
     * @return SHA-256 of the identity as 64 lowercase hex characters; never the identifier itself, which may be a
     *         password typed in the wrong field
     */
    public String keyFor(Long userId, String identifier) {
        if (userId != null) {
            return sha256("user:" + userId);
        }
        String typed = IdentifierNormalizer.lowerTrim(identifier);
        String phone = IdentifierNormalizer.normalizePhone(typed);
        return sha256("identifier:" + (IdentifierNormalizer.isPhone(phone) ? phone : typed));
    }

    /**
     * Refuses a login whose identity is locked, before the password is checked.
     *
     * <p>Throws {@code BusinessException} 429 {@code TOO_MANY_LOGIN_ATTEMPTS} while the lock lasts.
     *
     * @param key key from {@link #keyFor}
     */
    @Transactional(readOnly = true)
    public void checkNotLocked(String key) {
        Instant now = clock.instant();
        loginFailureRepository.findById(key)
                .filter(failure -> failure.getLockedUntil() != null && now.isBefore(failure.getLockedUntil()))
                .ifPresent(failure -> {
                    throw new BusinessException(ApiStatus.TOO_MANY_REQUESTS, "TOO_MANY_LOGIN_ATTEMPTS",
                            "Too many failed login attempts, please try again later");
                });
    }

    /**
     * Counts a failed login. The failure that reaches {@code auth.login.max-failures} within the window starts a lock
     * and a new count; a failure after the window ended starts a new count.
     *
     * @param key key from {@link #keyFor}
     */
    @Transactional
    public void recordFailure(String key) {
        Instant now = clock.instant();
        loginFailureRepository.insertIfAbsent(key, now);
        LoginFailure failure = loginFailureRepository.findByIdForUpdate(key)
                .orElseThrow(() -> new IllegalStateException("login_failures row missing right after its insert"));

        if (!now.isBefore(failure.getWindowStartedAt().plus(properties.failureWindow()))) {
            failure.setFailureCount(0);
            failure.setWindowStartedAt(now);
        }
        failure.setFailureCount(failure.getFailureCount() + 1);

        if (failure.getFailureCount() >= properties.maxFailures()) {
            failure.setLockedUntil(now.plus(properties.lockDuration()));
            failure.setFailureCount(0);
            failure.setWindowStartedAt(now);
            log.warn("[LOGIN] {} failed logins: identity {} locked until {}", properties.maxFailures(),
                    key.substring(0, 12), failure.getLockedUntil());
        }
    }

    /**
     * Forgets the failures of an identity after a successful login. Does nothing if it has none.
     *
     * @param key key from {@link #keyFor}
     */
    @Transactional
    public void reset(String key) {
        loginFailureRepository.deleteById(key);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime must provide SHA-256
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
