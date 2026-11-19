package com.fooddelivery.order.service;

import com.fooddelivery.order.client.AuthGrpcClient;
import com.fooddelivery.order.client.AuthUser;
import com.fooddelivery.order.dto.AuthCallResult;
import org.springframework.stereotype.Service;

/**
 * Logic of the test API {@code GET /order/test/auth-grpc}: fetches the caller from auth-service over gRPC and times the
 * call, to check that order-service reaches auth-service.
 */
@Service
public class AuthCallTestService {

    private final AuthGrpcClient authGrpcClient;

    public AuthCallTestService(AuthGrpcClient authGrpcClient) {
        this.authGrpcClient = authGrpcClient;
    }

    /**
     * Fetches the user over gRPC, with {@link AuthGrpcClient}.
     *
     * <p>Throws {@code BusinessException} as {@link AuthGrpcClient#getUser}: 404 {@code USER_NOT_FOUND}, 503
     * {@code AUTH_UNAVAILABLE}, 502 {@code AUTH_CALL_FAILED}.
     *
     * @param userId value of the {@code X-User-Id} header, checked by {@code AuthTestValidator}
     * @return the user and the duration of the call
     */
    public AuthCallResult callOverGrpc(long userId) {
        long start = System.nanoTime();
        AuthUser user = authGrpcClient.getUser(userId);
        return new AuthCallResult((System.nanoTime() - start) / 1_000_000, user);
    }
}
