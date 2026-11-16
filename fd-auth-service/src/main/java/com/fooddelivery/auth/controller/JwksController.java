package com.fooddelivery.auth.controller;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Publishes the public key of the access tokens as a JWK Set (RFC 7517), so the gateway and other services verify
 * tokens themselves, without calling this service on every request and without being able to sign one.
 *
 * <p>Outside the API flow: {@code /auth/.well-known/**} is in {@code api-flow.exclude-paths}, so the endpoint has no
 * route in {@code api-routes.xml}. The body is the standard JWK Set, not an {@code ApiResponse}, because JWT libraries
 * read that exact format.
 */
@RestController
@RequestMapping("/auth/.well-known")
public class JwksController {

    // RFC 7517 media type of a JWK Set; JWT libraries also accept application/json
    static final MediaType JWK_SET_JSON = MediaType.parseMediaType("application/jwk-set+json");

    // Verifiers may reuse the keys for 5 minutes. A new key must be published at least this long before tokens are
    // signed with it, or a verifier with a cached JWKS rejects them until it fetches again
    static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final Map<String, Object> jwkSet;

    // Built once at startup: the key pair never changes while the service runs
    public JwksController(RSAKey jwtSigningKey) {
        this.jwkSet = new JWKSet(jwtSigningKey.toPublicJWK()).toJSONObject(true);
    }

    /**
     * {@code GET /auth/.well-known/jwks.json}: the public key that verifies access tokens, with 200, e.g.
     * {@code {"keys":[{"kty":"RSA","kid":"fd-auth-dev-1","use":"sig","alg":"RS256","n":"...","e":"AQAB"}]}}.
     *
     * <p>Only public parameters ({@code n}, {@code e}) are published, never the private ones ({@code d}, {@code p},
     * {@code q}...). A verifier picks the key whose {@code kid} matches the {@code kid} in the token header.
     */
    @GetMapping("/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .contentType(JWK_SET_JSON)
                .cacheControl(CacheControl.maxAge(MAX_AGE).cachePublic())
                .body(jwkSet);
    }
}
