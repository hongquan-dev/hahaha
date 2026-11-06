package com.fooddelivery.auth.service.session;


import com.fooddelivery.auth.common.model.RefreshToken;
import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.config.properties.AuthProperties;
import com.fooddelivery.auth.repository.RefreshTokenRepository;
import com.fooddelivery.auth.repository.UserRepository;
import com.fooddelivery.auth.service.token.TokenService;
import com.fooddelivery.id.entity.EntityType;
import com.fooddelivery.id.entity.IdUtils;
import com.fooddelivery.web.exception.BusinessException;
import com.fooddelivery.web.status.ApiStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Login sessions, stored as chains of refresh tokens in {@code refresh_tokens}: starts one at login, rotates its token
 * at each refresh, ends it at logout.
 *
 * <p>A session is a family of tokens sharing one {@code familyId}, the ID of the token created at login. Only one token
 * of a family is usable at a time; each refresh revokes it and adds the next one. The raw tokens only travel in the
 * {@code refresh_token} cookie; this class receives them from the controller and stores their SHA-256 hash.
 */
@Service
public class SessionService {
    private static final Logger log = LogManager.getLogger(SessionService.class);

    // refresh_tokens.user_agent is 512 characters
    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final Duration idleTtl;
    private final Duration absoluteTtl;
    private final Duration reuseGrace;
    private final Clock clock;

    public SessionService(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository,
                          TokenService tokenService, AuthProperties properties, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.idleTtl = properties.refresh().idleTtl();
        this.absoluteTtl = properties.refresh().absoluteTtl();
        this.reuseGrace = properties.refresh().reuseGrace();
        this.clock = clock;
    }

    /**
     * Starts a new session for a user whose password was just checked: a new family whose first token expires after
     * {@code auth.refresh.idle-ttl}, the family after {@code auth.refresh.absolute-ttl}, plus a new access token.
     *
     * <p>Every login starts its own family, so ending one session (logout on a device) leaves the others alive.
     *
     * @param user      active user
     * @param userAgent {@code User-Agent} header, {@code null} if absent; truncated to 512 characters
     * @param ip        client IP
     * @return the tokens to set as cookies
     */
    @Transactional
    public IssuedTokens start(User user, String userAgent, String ip) {
        Instant now = clock.instant();
        long id = IdUtils.generate(EntityType.REFRESH_TOKEN);
        IssuedTokens tokens = issue(user, id, id, now.plus(absoluteTtl), now, userAgent, ip);
        log.info("[SESSION] Started session {} for user {}", id, user.getId());
        return tokens;
    }

    /**
     * Rotates the session of a refresh token: revokes the token and returns a new access token and the next refresh
     * token of the same family. The new token expires after {@code auth.refresh.idle-ttl}; the family keeps its
     * absolute expiry.
     *
     * <p>The token row is locked while it is checked and rotated, so of two requests with the same token only one
     * rotates it. Throws {@code BusinessException}:
     * <ul>
     *   <li>401 {@code REFRESH_TOKEN_INVALID}: no token, unknown token, token revoked by a logout, expired token or
     *       family, or a user that no longer exists or is disabled (its family is then revoked)</li>
     *   <li>409 {@code REFRESH_CONFLICT}: the token was replaced less than {@code auth.refresh.reuse-grace} ago, i.e.
     *       another tab refreshed with it first; the browser already holds the new cookie, so the client only retries
     *       its request</li>
     *   <li>401 {@code REFRESH_TOKEN_REUSED}: the token was replaced earlier than that, so a copy of it is being
     *       replayed; the whole family is revoked</li>
     * </ul>
     *
     * <p>Revocations made before a 401 are committed ({@code noRollbackFor}), since the exception is the expected way
     * to report them.
     *
     * @param refreshToken value of the {@code refresh_token} cookie, {@code null} if absent
     * @param userAgent    {@code User-Agent} header of the request, {@code null} if absent
     * @param ip           client IP
     * @return the tokens to set as cookies
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public IssuedTokens refresh(String refreshToken, String userAgent, String ip) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw invalidRefreshToken();
        }
        RefreshToken current = refreshTokenRepository.findByTokenHashForUpdate(tokenService.hash(refreshToken))
                .orElseThrow(SessionService::invalidRefreshToken);
        Instant now = clock.instant();

        if (current.getRevokedAt() != null) {
            if (current.getReplacedAt() == null) {
                // Revoked without a successor: logout or a family revocation, not a rotation
                throw invalidRefreshToken();
            }
            if (!now.isAfter(current.getReplacedAt().plus(reuseGrace))) {
                log.info("[SESSION] Concurrent refresh of session {}, answered with 409", current.getFamilyId());
                throw new BusinessException(ApiStatus.CONFLICT, "REFRESH_CONFLICT",
                        "Session was just refreshed by another request");
            }
            int revoked = refreshTokenRepository.revokeFamily(current.getFamilyId(), now);
            log.warn("[SESSION] Replaced refresh token reused: session {} of user {} revoked ({} tokens)",
                    current.getFamilyId(), current.getUserId(), revoked);
            throw new BusinessException(ApiStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED",
                    "Session ended for security reasons, please log in again");
        }

        if (now.isAfter(current.getExpiresAt()) || now.isAfter(current.getFamilyExpiresAt())) {
            throw invalidRefreshToken();
        }

        User user = userRepository.findById(current.getUserId()).filter(User::isActive).orElse(null);
        if (user == null) {
            refreshTokenRepository.revokeFamily(current.getFamilyId(), now);
            log.warn("[SESSION] User {} is missing or disabled: session {} revoked", current.getUserId(),
                    current.getFamilyId());
            throw invalidRefreshToken();
        }

        current.setRevokedAt(now);
        current.setReplacedAt(now);
        IssuedTokens tokens = issue(user, IdUtils.generate(EntityType.REFRESH_TOKEN), current.getFamilyId(),
                current.getFamilyExpiresAt(), now, userAgent, ip);
        log.info("[SESSION] Refreshed session {} of user {}", current.getFamilyId(), user.getId());
        return tokens;
    }

    /**
     * Ends the session of a refresh token by revoking its whole family. Does nothing if the token is absent or
     * unknown, so logging out never fails.
     *
     * @param refreshToken value of the {@code refresh_token} cookie, {@code null} if absent
     */
    @Transactional
    public void end(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(tokenService.hash(refreshToken)).ifPresent(token -> {
            int revoked = refreshTokenRepository.revokeFamily(token.getFamilyId(), clock.instant());
            log.info("[SESSION] Ended session {} of user {} ({} tokens revoked)", token.getFamilyId(),
                    token.getUserId(), revoked);
        });
    }

    // Saves a new refresh token of a family and signs an access token for its user
    private IssuedTokens issue(User user, long id, long familyId, Instant familyExpiresAt, Instant now,
                               String userAgent, String ip) {
        String refreshToken = tokenService.generateRefreshToken();
        Instant expiresAt = now.plus(idleTtl);
        refreshTokenRepository.save(new RefreshToken(id, user.getId(), tokenService.hash(refreshToken), familyId,
                expiresAt, familyExpiresAt, truncate(userAgent), ip));
        return new IssuedTokens(tokenService.issueAccessToken(user), refreshToken, expiresAt, familyExpiresAt);
    }

    private static String truncate(String userAgent) {
        return userAgent == null || userAgent.length() <= MAX_USER_AGENT_LENGTH
                ? userAgent : userAgent.substring(0, MAX_USER_AGENT_LENGTH);
    }

    private static BusinessException invalidRefreshToken() {
        return new BusinessException(ApiStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID",
                "Session has expired, please log in again");
    }
}
