package com.fooddelivery.auth.service.token;

import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.config.properties.AuthProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link TokenService}, without Spring or a database. Each service gets a fixed {@link Clock}, so expiry
 * is tested by building a second service with a later clock instead of waiting.
 */
class TokenServiceTest {

    private static final String KEY_ID = "test-kid";
    private static final String ISSUER = "fd-auth-service";
    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Instant NOW = Instant.parse("2026-11-06T08:00:00Z");

    private static final User USER = new User(17939477151001L, "quan", "hash", "quan@test.com", "+84912345678",
            "CUSTOMER");

    // Generated once: RSA key generation takes a noticeable fraction of a second
    private static RSAKey key;
    private static RSAKey otherKey;

    @BeforeAll
    static void generateKeys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        otherKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
    }

    @Test
    void issuedTokenIsValidWithUserIdAndRole() {
        String token = service(key, ISSUER, NOW).issueAccessToken(USER);

        AccessTokenVerification result = service(key, ISSUER, NOW).verifyAccessToken(token);

        assertThat(result.status()).isEqualTo(AccessTokenVerification.Status.VALID);
        assertThat(result.userId()).isEqualTo(USER.getId());
        assertThat(result.role()).isEqualTo("CUSTOMER");
    }

    @Test
    void issuedTokenHasExpectedHeaderAndClaims() throws Exception {
        SignedJWT jwt = SignedJWT.parse(service(key, ISSUER, NOW).issueAccessToken(USER));

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(KEY_ID);
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getSubject()).isEqualTo("17939477151001");
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(NOW.plus(ACCESS_TTL));
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getClaims()).doesNotContainKeys("username", "email", "phoneNumber");
    }

    @Test
    void tokenIsStillValidWithinClockSkewAfterExpiry() {
        String token = service(key, ISSUER, NOW).issueAccessToken(USER);
        Instant justInsideSkew = NOW.plus(ACCESS_TTL).plus(TokenService.CLOCK_SKEW).minusSeconds(1);

        assertThat(service(key, ISSUER, justInsideSkew).verifyAccessToken(token).status())
                .isEqualTo(AccessTokenVerification.Status.VALID);
    }

    @Test
    void tokenPastExpiryAndSkewIsExpired() {
        String token = service(key, ISSUER, NOW).issueAccessToken(USER);
        Instant afterSkew = NOW.plus(ACCESS_TTL).plus(TokenService.CLOCK_SKEW).plusSeconds(1);

        AccessTokenVerification result = service(key, ISSUER, afterSkew).verifyAccessToken(token);

        assertThat(result.status()).isEqualTo(AccessTokenVerification.Status.EXPIRED);
        assertThat(result.userId()).isNull();
    }

    @Test
    void tamperedPayloadIsInvalid() {
        String[] parts = service(key, ISSUER, NOW).issueAccessToken(USER).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forgedPayload = payload.replace("\"CUSTOMER\"", "\"ADMIN\"");
        assertThat(forgedPayload).isNotEqualTo(payload);
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertInvalid(tampered);
    }

    @Test
    void tokenSignedWithAnotherKeyIsInvalid() {
        assertInvalid(service(otherKey, ISSUER, NOW).issueAccessToken(USER));
    }

    @Test
    void forgedTokenPastExpiryIsInvalidNotExpired() {
        String forged = service(otherKey, ISSUER, NOW).issueAccessToken(USER);
        Instant later = NOW.plus(ACCESS_TTL).plus(Duration.ofHours(1));

        assertThat(service(key, ISSUER, later).verifyAccessToken(forged).status())
                .isEqualTo(AccessTokenVerification.Status.INVALID);
    }

    @Test
    void unsignedTokenWithAlgNoneIsInvalid() {
        assertInvalid(new PlainJWT(validClaims()).serialize());
    }

    @Test
    void hs256TokenSignedWithThePublicKeyIsInvalid() throws Exception {
        // Algorithm confusion: an attacker signs with HMAC, using the public key (known to everyone) as the secret
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KEY_ID).build(), validClaims());
        jwt.sign(new MACSigner(key.toRSAPublicKey().getEncoded()));

        assertInvalid(jwt.serialize());
    }

    @Test
    void tokenFromAnotherIssuerIsInvalid() {
        assertInvalid(service(key, "evil-service", NOW).issueAccessToken(USER));
    }

    @Test
    void tokenWithAnotherKeyIdIsInvalid() throws Exception {
        RSAKey sameKeyOtherId = new RSAKey.Builder(key).keyID("other-kid").build();

        assertInvalid(service(sameKeyOtherId, ISSUER, NOW).issueAccessToken(USER));
    }

    @Test
    void missingOrMalformedTokenIsInvalidWithoutThrowing() {
        assertInvalid(null);
        assertInvalid("");
        assertInvalid("   ");
        assertInvalid("abc");
        assertInvalid("a.b.c");
        assertInvalid("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl");
    }

    @Test
    void refreshTokenIs43Base64UrlCharacters() {
        String token = service(key, ISSUER, NOW).generateRefreshToken();

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void refreshTokensAreUnique() {
        TokenService service = service(key, ISSUER, NOW);
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(service.generateRefreshToken());
        }

        assertThat(tokens).hasSize(1000);
    }

    @Test
    void hashIsSha256InLowercaseHex() {
        TokenService service = service(key, ISSUER, NOW);

        // Standard SHA-256 test vector for "abc"
        assertThat(service.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(service.hash("abc")).isEqualTo(service.hash("abc"));
        assertThat(service.hash(service.generateRefreshToken())).matches("[0-9a-f]{64}");
    }

    // Raw key texts are never read by TokenService (it gets the RSAKey bean), so placeholders are enough
    private static TokenService service(RSAKey signingKey, String issuer, Instant now) {
        AuthProperties properties = new AuthProperties(
                new AuthProperties.Jwt("unused", "unused", signingKey.getKeyID(), issuer, ACCESS_TTL),
                new AuthProperties.Refresh(Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(10)),
                new AuthProperties.Cookie(true),
                List.of("http://localhost:5173"));
        return new TokenService(signingKey, properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static JWTClaimsSet validClaims() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(String.valueOf(USER.getId()))
                .claim("role", "ADMIN")
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plus(ACCESS_TTL)))
                .build();
    }

    private static void assertInvalid(String token) {
        assertThat(service(key, ISSUER, NOW).verifyAccessToken(token).status())
                .isEqualTo(AccessTokenVerification.Status.INVALID);
    }
}
