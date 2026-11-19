package com.fooddelivery.order.dto;

import com.fooddelivery.order.client.AuthUser;

/**
 * Body of the test API {@code GET /order/test/auth-grpc}: the user fetched from auth-service over gRPC, and how long
 * the call took.
 *
 * @param elapsedMs duration of the call to auth-service only, in milliseconds
 * @param user      the user of the {@code X-User-Id} header, as auth-service returned it
 */
public record AuthCallResult(long elapsedMs, AuthUser user) {
}
