package com.fooddelivery.gateway.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Date;

import static com.fooddelivery.gateway.auth.AccessTokenVerifierTestSupport.KEY_ID;
import static com.fooddelivery.gateway.auth.AccessTokenVerifierTestSupport.NOW;
import static com.fooddelivery.gateway.auth.AccessTokenVerifierTestSupport.claims;
import static com.fooddelivery.gateway.auth.AccessTokenVerifierTestSupport.key;
import static com.fooddelivery.gateway.auth.AccessTokenVerifierTestSupport.otherKey;
import static com.fooddelivery.gateway.auth.AccessTokenVerifierTestSupport.sign;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link AccessTokenVerifier}, without Spring or auth-service: tokens are signed with a generated key
 * pair, and the key source is a lambda that knows only {@code KEY_ID} (see {@link AccessTokenVerifierTestSupport}).
 */
class AccessTokenVerifierTest {

    @Test
    void validTokenGivesUserIdAndRole() {
        TokenVerification result = verify(sign(key(), claims().build()));

        assertThat(result.status()).isEqualTo(TokenVerification.Status.VALID);
        assertThat(result.userId()).isEqualTo(17939477151001L);
        assertThat(result.role()).isEqualTo("CUSTOMER");
    }

    @Test
    void tokenWithoutRoleIsValidWithNullRole() {
        TokenVerification result = verify(sign(key(), claims().claim("role", null).build()));

        assertThat(result.status()).isEqualTo(TokenVerification.Status.VALID);
        assertThat(result.role()).isNull();
    }

    @Test
    void expiryAllowsTheClockSkewThenReportsExpired() {
        String token = sign(key(), claims().expirationTime(Date.from(NOW)).build());

        assertThat(verify(token, NOW.plus(AccessTokenVerifier.CLOCK_SKEW)).status())
                .isEqualTo(TokenVerification.Status.VALID);
        assertThat(verify(token, NOW.plus(AccessTokenVerifier.CLOCK_SKEW).plusSeconds(1)).status())
                .isEqualTo(TokenVerification.Status.EXPIRED);
    }

    @Test
    void forgedExpiredTokenIsInvalidNotExpired() {
        String token = sign(otherKey(), claims().expirationTime(Date.from(NOW.minusSeconds(3600))).build());

        assertThat(verify(token).status()).isEqualTo(TokenVerification.Status.INVALID);
    }

    @Test
    void tokenSignedWithAnotherKeyIsInvalid() {
        assertThat(verify(sign(otherKey(), claims().build())).status()).isEqualTo(TokenVerification.Status.INVALID);
    }

    @Test
    void modifiedPayloadIsInvalid() {
        String[] parts = sign(key(), claims().build()).split("\\.");
        String adminPayload = Base64URL.encode(claims().claim("role", "ADMIN").build().toString()).toString();

        assertThat(verify(parts[0] + "." + adminPayload + "." + parts[2]).status())
                .isEqualTo(TokenVerification.Status.INVALID);
    }

    @Test
    void wrongIssuerIsInvalid() {
        assertThat(verify(sign(key(), claims().issuer("someone-else").build())).status())
                .isEqualTo(TokenVerification.Status.INVALID);
    }

    @Test
    void nonNumericSubjectOrMissingExpiryIsInvalid() {
        assertThat(verify(sign(key(), claims().subject("quan").build())).status())
                .isEqualTo(TokenVerification.Status.INVALID);
        assertThat(verify(sign(key(), claims().expirationTime(null).build())).status())
                .isEqualTo(TokenVerification.Status.INVALID);
    }

    @Test
    void algNoneAndHs256AreRejected() throws Exception {
        String unsigned = new PlainJWT(claims().build()).serialize();

        SignedJWT hs256 = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KEY_ID).build(),
                claims().build());
        hs256.sign(new MACSigner(new byte[32]));

        assertThat(verify(unsigned).status()).isEqualTo(TokenVerification.Status.INVALID);
        assertThat(verify(hs256.serialize()).status()).isEqualTo(TokenVerification.Status.INVALID);
    }

    @Test
    void missingOrUnknownKidIsInvalid() throws Exception {
        SignedJWT noKid = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims().build());
        noKid.sign(new RSASSASigner(key()));
        SignedJWT unknownKid = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("other").build(),
                claims().build());
        unknownKid.sign(new RSASSASigner(key()));

        assertThat(verify(noKid.serialize()).status()).isEqualTo(TokenVerification.Status.INVALID);
        assertThat(verify(unknownKid.serialize()).status()).isEqualTo(TokenVerification.Status.INVALID);
    }

    @Test
    void missingOrGarbageTokenIsInvalid() {
        assertThat(verify(null).status()).isEqualTo(TokenVerification.Status.INVALID);
        assertThat(verify(" ").status()).isEqualTo(TokenVerification.Status.INVALID);
        assertThat(verify("not.a.jwt").status()).isEqualTo(TokenVerification.Status.INVALID);
    }

    @Test
    void keysThatCannotBeFetchedGiveUnavailable() {
        AccessTokenVerifier verifier = AccessTokenVerifierTestSupport.verifier(
                keyId -> Mono.error(new JwksUnavailableException("down", new RuntimeException())));

        assertThat(verifier.verify(sign(key(), claims().build())).block().status())
                .isEqualTo(TokenVerification.Status.UNAVAILABLE);
    }

    private static TokenVerification verify(String token) {
        return verify(token, NOW);
    }

    private static TokenVerification verify(String token, Instant now) {
        return AccessTokenVerifierTestSupport.verifier(now).verify(token).block();
    }
}
