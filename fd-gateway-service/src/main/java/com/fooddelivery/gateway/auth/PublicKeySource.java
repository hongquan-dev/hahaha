package com.fooddelivery.gateway.auth;

import com.nimbusds.jose.jwk.RSAKey;
import reactor.core.publisher.Mono;

/**
 * Where {@link AccessTokenVerifier} finds the public key named by the {@code kid} of a token. Implemented by
 * {@link JwksKeyProvider}; tests pass a lambda instead.
 */
@FunctionalInterface
public interface PublicKeySource {

    /**
     * Finds the RSA public key with the given {@code kid}.
     *
     * @param keyId {@code kid} from the token header, never {@code null}
     * @return the key; empty if no key has this {@code kid}; a {@link JwksUnavailableException} error if the keys
     *         cannot be fetched and none were fetched before
     */
    Mono<RSAKey> findKey(String keyId);
}
