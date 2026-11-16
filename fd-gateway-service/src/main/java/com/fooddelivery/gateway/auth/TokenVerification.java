package com.fooddelivery.gateway.auth;

/**
 * Result of {@link AccessTokenVerifier#verify}: whether the request may go on to the service and, if so, as whom.
 *
 * <p>Created only through the static factories, so {@code userId} is set exactly when the status is
 * {@link Status#VALID}.
 *
 * @param status outcome of the checks
 * @param userId {@code sub} claim, {@code null} unless {@code VALID}
 * @param role   {@code role} claim, e.g. {@code CUSTOMER}; {@code null} unless {@code VALID}, or if the token has none
 */
public record TokenVerification(Status status, Long userId, String role) {

    /**
     * Outcome of the checks, mapped by {@code JwtAuthGatewayFilterFactory} to a response.
     *
     * <ul>
     *   <li>{@code VALID}: signed by auth-service, right issuer, not expired; the request goes on</li>
     *   <li>{@code EXPIRED}: genuine but past {@code exp}; 401 {@code TOKEN_EXPIRED}, the client should refresh</li>
     *   <li>{@code INVALID}: missing, malformed, wrong algorithm, key, signature, issuer or subject; 401
     *       {@code UNAUTHENTICATED}</li>
     *   <li>{@code UNAVAILABLE}: the public keys could not be fetched, so nothing was checked; 503
     *       {@code AUTH_UNAVAILABLE}</li>
     * </ul>
     */
    public enum Status { VALID, EXPIRED, INVALID, UNAVAILABLE }

    public static TokenVerification valid(Long userId, String role) {
        return new TokenVerification(Status.VALID, userId, role);
    }

    public static TokenVerification expired() {
        return new TokenVerification(Status.EXPIRED, null, null);
    }

    public static TokenVerification invalid() {
        return new TokenVerification(Status.INVALID, null, null);
    }

    public static TokenVerification unavailable() {
        return new TokenVerification(Status.UNAVAILABLE, null, null);
    }
}
