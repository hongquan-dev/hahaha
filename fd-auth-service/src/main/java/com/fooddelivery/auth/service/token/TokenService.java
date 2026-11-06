package com.fooddelivery.auth.service.token;

import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.config.properties.AuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Creates and checks the tokens of a login session: RS256 access tokens (JWT) and opaque refresh tokens.
 *
 * <p>Pure computation: no database access and no cookies, which belong to {@code AuthService} and the controller.
 * The key pair comes from {@code JwtKeyConfig}, the issuer and lifetime from {@link AuthProperties}, the current time
 * from the injected {@link Clock}. Thread-safe.
 */
@Service
public class TokenService {
    private static final Logger log = LogManager.getLogger(TokenService.class);

    // Tolerated difference between the clock of the issuer and of a verifier, applied to exp. Without it a token
    // could look expired on a service whose clock runs a few seconds ahead
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    // 256 bits: too many values to guess, so the refresh token needs no signature
    private static final int REFRESH_TOKEN_BYTES = 32;

    private static final String ROLE_CLAIM = "role";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RSAKey signingKey;
    private final JWSSigner signer;
    private final JWSVerifier verifier;
    private final String issuer;
    private final Duration accessTtl;
    private final Clock clock;

    public TokenService(RSAKey jwtSigningKey, AuthProperties properties, Clock clock) {
        this.signingKey = jwtSigningKey;
        this.issuer = properties.jwt().issuer();
        this.accessTtl = properties.jwt().accessTtl();
        this.clock = clock;
        try {
            this.signer = new RSASSASigner(jwtSigningKey);
            this.verifier = new RSASSAVerifier(jwtSigningKey.toRSAPublicKey());
        } catch (JOSEException e) {
            // JwtKeyConfig already checked the key pair, so this means a broken key bean
            throw new IllegalStateException("Cannot use the JWT signing key: " + e.getMessage(), e);
        }
    }

    /**
     * Creates an access token for a user, valid for {@code auth.jwt.access-ttl} from now.
     *
     * <p>Header {@code alg=RS256}, {@code typ=JWT}, {@code kid} of the key. Claims {@code iss}, {@code sub} (user ID),
     * {@code role}, {@code iat}, {@code exp} and a random {@code jti}. Anyone holding the token can read the claims,
     * so nothing personal (username, email, phone number) is put in them.
     *
     * @param user saved user, with its ID and role
     * @return the signed token in compact form {@code header.payload.signature}
     */
    public String issueAccessToken(User user) {
        Instant now = clock.instant();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(signingKey.getKeyID())
                .build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(String.valueOf(user.getId()))
                .claim(ROLE_CLAIM, user.getRole())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(accessTtl)))
                .jwtID(UUID.randomUUID().toString())
                .build();

        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the access token: " + e.getMessage(), e);
        }
        return jwt.serialize();
    }

    /**
     * Checks an access token received from a client, in this order: JWT format, algorithm RS256 (the {@code alg} the
     * token declares is not trusted, so {@code none} or HS256 are rejected), {@code kid}, signature, issuer, then
     * expiry with {@link #CLOCK_SKEW}.
     *
     * <p>Expiry is checked last, so only a genuine token can be reported as {@code EXPIRED}; a forged token with a past
     * {@code exp} is {@code INVALID}. Never throws: a bad token is a normal outcome, not an error. The reason of an
     * {@code INVALID} result is logged at debug level; the token itself is never logged.
     *
     * @param token value of the {@code access_token} cookie, {@code null} if the cookie is absent
     * @return {@code VALID} with the user ID and role, {@code EXPIRED} or {@code INVALID}
     */
    public AccessTokenVerification verifyAccessToken(String token) {
        if (token == null || token.isBlank()) {
            return AccessTokenVerification.invalid();
        }

        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(token);
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            return invalid("not a signed JWT: " + e.getMessage());
        }

        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            return invalid("algorithm " + jwt.getHeader().getAlgorithm() + " instead of RS256");
        }
        // Only one key exists for now; a token naming another one was not signed by it
        if (!signingKey.getKeyID().equals(jwt.getHeader().getKeyID())) {
            return invalid("unknown kid " + jwt.getHeader().getKeyID());
        }
        try {
            if (!jwt.verify(verifier)) {
                return invalid("signature mismatch");
            }
        } catch (JOSEException e) {
            return invalid("signature check failed: " + e.getMessage());
        }

        if (!issuer.equals(claims.getIssuer())) {
            return invalid("issuer " + claims.getIssuer());
        }
        Long userId = parseUserId(claims.getSubject());
        if (userId == null) {
            return invalid("subject " + claims.getSubject() + " is not a user ID");
        }
        Date expiration = claims.getExpirationTime();
        if (expiration == null) {
            return invalid("no exp claim");
        }
        if (clock.instant().isAfter(expiration.toInstant().plus(CLOCK_SKEW))) {
            return AccessTokenVerification.expired();
        }

        Object role = claims.getClaim(ROLE_CLAIM);
        return AccessTokenVerification.valid(userId, role instanceof String text ? text : null);
    }

    /**
     * Creates a new refresh token: 32 bytes from {@link SecureRandom}, Base64URL without padding.
     *
     * @return 43 characters of {@code A-Z a-z 0-9 - _}, safe in a cookie without escaping
     */
    public String generateRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 of a refresh token, the form stored in and looked up from {@code refresh_tokens.token_hash}.
     *
     * @param refreshToken raw token, as generated or read from the cookie
     * @return 64 lowercase hex characters, always the same for the same token
     */
    public String hash(String refreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime must provide SHA-256
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    // User ID from the sub claim, null if absent or not a number
    private static Long parseUserId(String subject) {
        if (subject == null) {
            return null;
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static AccessTokenVerification invalid(String reason) {
        log.debug("[TOKEN] Invalid access token: {}", reason);
        return AccessTokenVerification.invalid();
    }
}
