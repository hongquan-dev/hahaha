package com.fooddelivery.auth.service.token;

/**
 * Result of {@link TokenService#verifyAccessToken}: whether an access token can be trusted and, if so, whose it is.
 *
 * <p>Created only through the static factories, so {@code userId} and {@code role} are set exactly when the status is
 * {@link Status#VALID}.
 *
 * @param status outcome of the checks
 * @param userId {@code sub} claim, {@code null} unless {@code VALID}
 * @param role   {@code role} claim, e.g. {@code CUSTOMER}; {@code null} unless {@code VALID}
 */
public record AccessTokenVerification(Status status, Long userId, String role) {

    /**
     * Outcome of the checks, mapped by the caller to a response.
     *
     * <ul>
     *   <li>{@code VALID}: signed by this service, right issuer, not expired</li>
     *   <li>{@code EXPIRED}: genuine (signature and issuer checked) but past {@code exp}; the client should refresh,
     *       answered with 401 {@code TOKEN_EXPIRED}</li>
     *   <li>{@code INVALID}: missing, malformed, wrong algorithm, key, signature or issuer; answered with 401
     *       {@code UNAUTHENTICATED}</li>
     * </ul>
     */
    public enum Status { VALID, EXPIRED, INVALID }

    public static AccessTokenVerification valid(Long userId, String role) {
        return new AccessTokenVerification(Status.VALID, userId, role);
    }

    public static AccessTokenVerification expired() {
        return new AccessTokenVerification(Status.EXPIRED, null, null);
    }

    public static AccessTokenVerification invalid() {
        return new AccessTokenVerification(Status.INVALID, null, null);
    }

    public boolean isValid() { return status == Status.VALID; }
}
