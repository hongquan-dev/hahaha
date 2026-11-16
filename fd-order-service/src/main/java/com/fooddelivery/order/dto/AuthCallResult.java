package com.fooddelivery.order.dto;

import com.fooddelivery.order.client.AuthUser;

/**
 * Body of the test APIs {@code GET /order/test/auth-http} and {@code GET /order/test/auth-grpc}: the same user
 * fetched from auth-service over one transport, and how long the call took.
 *
 * @param transport {@code HTTP} or {@code gRPC}
 * @param elapsedMs duration of the call to auth-service only, in milliseconds
 * @param user      the user of the {@code X-User-Id} header, as auth-service returned it
 */
public record AuthCallResult(String transport, long elapsedMs, AuthUser user) {
}
