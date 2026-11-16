package com.fooddelivery.auth.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link JwksController}, without Spring: the published JWK Set must hold only the public key, with the
 * metadata a verifier needs, and that key must verify tokens signed with the private one.
 */
class JwksControllerTest {

    private static final String KEY_ID = "test-kid";

    // Generated once: RSA key generation takes a noticeable fraction of a second
    private static RSAKey key;

    @BeforeAll
    static void generateKey() throws Exception {
        key = new RSAKeyGenerator(2048).keyID(KEY_ID).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                .generate();
    }

    @Test
    void returnsJwkSetWithCacheHeaders() {
        ResponseEntity<Map<String, Object>> response = new JwksController(key).jwks();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(JwksController.JWK_SET_JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=300, public");
    }

    @Test
    void publishesOnlyThePublicKeyWithItsMetadata() {
        Map<String, Object> jwk = onlyKey(new JwksController(key).jwks().getBody());

        assertThat(jwk).containsEntry("kty", "RSA")
                .containsEntry("kid", KEY_ID)
                .containsEntry("use", "sig")
                .containsEntry("alg", "RS256")
                .containsKeys("n", "e")
                .doesNotContainKeys("d", "p", "q", "dp", "dq", "qi", "oth");
    }

    @Test
    void publishedKeyVerifiesTokensSignedWithThePrivateKey() throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(),
                new JWTClaimsSet.Builder().subject("17939477151001").build());
        token.sign(new RSASSASigner(key));

        JWKSet published = JWKSet.parse(new JwksController(key).jwks().getBody());
        RSAKey publicKey = (RSAKey) published.getKeyByKeyId(token.getHeader().getKeyID());

        assertThat(publicKey.isPrivate()).isFalse();
        assertThat(token.verify(new RSASSAVerifier(publicKey))).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyKey(Map<String, Object> jwkSet) {
        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwkSet.get("keys");
        assertThat(keys).hasSize(1);
        return keys.get(0);
    }
}
