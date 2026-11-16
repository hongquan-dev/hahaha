package com.fooddelivery.gateway.auth;

/**
 * Thrown at request time by {@link JwksKeyProvider} when the public keys cannot be fetched from auth-service (down,
 * timeout, malformed response) and no keys were fetched before, so no token can be checked; the request is answered
 * with 503 {@code AUTH_UNAVAILABLE}.
 */
public class JwksUnavailableException extends RuntimeException {

    public JwksUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
