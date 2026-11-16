package com.fooddelivery.gateway.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;

/**
 * Checks the access tokens issued by auth-service, at request time, with the public key from {@link PublicKeySource}.
 *
 * <p>Same checks and order as {@code TokenService.verifyAccessToken} of auth-service: JWT format, algorithm RS256
 * (the {@code alg} the token declares is not trusted, so {@code none} or HS256 are rejected), {@code kid}, signature,
 * issuer, subject, then expiry with {@link #CLOCK_SKEW}. Expiry is checked last, so only a genuine token can be
 * reported as {@code EXPIRED}. Never fails: every outcome, including keys that cannot be fetched, is a
 * {@link TokenVerification}. Thread-safe.
 */
@Component
public class AccessTokenVerifier {
    private static final Logger log = LogManager.getLogger(AccessTokenVerifier.class);

    // Same tolerance as auth-service, so a token is not valid there and expired here because of a few seconds
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private static final String ROLE_CLAIM = "role";

    private final PublicKeySource keySource;
    private final String issuer;
    private final Clock clock;

    public AccessTokenVerifier(PublicKeySource keySource, GatewayAuthProperties properties, Clock clock) {
        this.keySource = keySource;
        this.issuer = properties.issuer();
        this.clock = clock;
    }

    /**
     * Checks one access token.
     *
     * @param token value of the {@code access_token} cookie or of the {@code Authorization: Bearer} header,
     *              {@code null} if the request has neither
     * @return the outcome, never an error; {@code UNAVAILABLE} if the public keys cannot be fetched
     */
    public Mono<TokenVerification> verify(String token) {
        if (token == null || token.isBlank()) {
            return Mono.just(TokenVerification.invalid());
        }

        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(token);
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            return Mono.just(invalid("not a signed JWT: " + e.getMessage()));
        }

        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            return Mono.just(invalid("algorithm " + jwt.getHeader().getAlgorithm() + " instead of RS256"));
        }
        String keyId = jwt.getHeader().getKeyID();
        if (keyId == null) {
            return Mono.just(invalid("no kid"));
        }

        return keySource.findKey(keyId)
                .map(key -> check(jwt, claims, key))
                .switchIfEmpty(Mono.fromSupplier(() -> invalid("unknown kid " + keyId)))
                .onErrorResume(JwksUnavailableException.class, e -> Mono.just(TokenVerification.unavailable()));
    }

    // Signature, issuer, subject and expiry, once the key named by the token's kid is known
    private TokenVerification check(SignedJWT jwt, JWTClaimsSet claims, RSAKey key) {
        try {
            if (!jwt.verify(new RSASSAVerifier(key))) {
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
            return TokenVerification.expired();
        }

        Object role = claims.getClaim(ROLE_CLAIM);
        return TokenVerification.valid(userId, role instanceof String text ? text : null);
    }

    // The user ID of the sub claim, e.g. "17939477151001" -> 17939477151001; null if absent or not a number
    private static Long parseUserId(String subject) {
        if (subject == null) {
            return null;
        }
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // The reason is only logged, never sent to the client, so a forger learns nothing about which check failed
    private static TokenVerification invalid(String reason) {
        log.debug("[TOKEN] Invalid access token: {}", reason);
        return TokenVerification.invalid();
    }
}
