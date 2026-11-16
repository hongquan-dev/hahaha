package com.fooddelivery.gateway.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

/**
 * Keys, tokens and a ready {@link AccessTokenVerifier} for the gateway tests, without Spring or auth-service. Tokens
 * are signed here as auth-service signs them, and the verifier's clock is fixed at {@link #NOW}.
 */
public final class AccessTokenVerifierTestSupport {

    public static final String KEY_ID = "test-kid";
    public static final String ISSUER = "fd-auth-service";
    public static final Instant NOW = Instant.parse("2026-11-16T08:00:00Z");

    // Generated once per test run: RSA key generation takes a noticeable fraction of a second
    private static RSAKey key;
    private static RSAKey otherKey;

    private AccessTokenVerifierTestSupport() {}

    // Key pair of "auth-service" in the tests
    public static synchronized RSAKey key() {
        if (key == null) {
            key = generate();
        }
        return key;
    }

    // Another key pair with the same kid, as a forger would use
    public static synchronized RSAKey otherKey() {
        if (otherKey == null) {
            otherKey = generate();
        }
        return otherKey;
    }

    // Claims of a token issued at NOW for 15 minutes to user 17939477151001, as auth-service issues them
    public static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("17939477151001")
                .claim("role", "CUSTOMER")
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plus(Duration.ofMinutes(15))))
                .jwtID("jti");
    }

    // RS256 token with KEY_ID in its header
    public static String sign(RSAKey signingKey, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String validToken() {
        return sign(key(), claims().build());
    }

    // Genuine, but expired an hour before NOW
    public static String expiredToken() {
        return sign(key(), claims().expirationTime(Date.from(NOW.minusSeconds(3600))).build());
    }

    public static GatewayAuthProperties properties() {
        return new GatewayAuthProperties(URI.create("http://localhost/jwks"), ISSUER, Duration.ofMinutes(5),
                Duration.ofSeconds(30), Duration.ofSeconds(2), List.of("/order/health"));
    }

    // Verifier at NOW whose key source knows only KEY_ID, with the public half of key()
    public static AccessTokenVerifier verifier() {
        return verifier(NOW);
    }

    public static AccessTokenVerifier verifier(Instant now) {
        PublicKeySource keySource = keyId -> KEY_ID.equals(keyId) ? Mono.just(key().toPublicJWK()) : Mono.empty();
        return new AccessTokenVerifier(keySource, properties(), Clock.fixed(now, ZoneOffset.UTC));
    }

    public static AccessTokenVerifier verifier(PublicKeySource keySource) {
        return new AccessTokenVerifier(keySource, properties(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
