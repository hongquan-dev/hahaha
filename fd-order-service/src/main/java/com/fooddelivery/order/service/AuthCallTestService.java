package com.fooddelivery.order.service;

import com.fooddelivery.order.client.AuthGrpcClient;
import com.fooddelivery.order.client.AuthHttpClient;
import com.fooddelivery.order.client.AuthUser;
import com.fooddelivery.order.dto.AuthCallResult;
import org.springframework.stereotype.Service;

import java.util.function.LongFunction;

/**
 * Logic of the test APIs under {@code /order/test}: fetches the caller from auth-service over HTTP or gRPC and times
 * the call, to check that both transports work and return the same user.
 */
@Service
public class AuthCallTestService {

    private final AuthHttpClient authHttpClient;
    private final AuthGrpcClient authGrpcClient;

    public AuthCallTestService(AuthHttpClient authHttpClient, AuthGrpcClient authGrpcClient) {
        this.authHttpClient = authHttpClient;
        this.authGrpcClient = authGrpcClient;
    }

    /**
     * Fetches the user over HTTP, with {@link AuthHttpClient}.
     *
     * <p>Throws {@code BusinessException} as {@link AuthHttpClient#getUser}: 404 {@code USER_NOT_FOUND}, 503
     * {@code AUTH_UNAVAILABLE}, 502 {@code AUTH_CALL_FAILED}.
     *
     * @param userId value of the {@code X-User-Id} header, checked by {@code AuthTestValidator}
     * @return the user with {@code transport} {@code HTTP} and the duration of the call
     */
    public AuthCallResult callOverHttp(long userId) {
        return timed("HTTP", userId, authHttpClient::getUser);
    }

    /**
     * Fetches the user over gRPC, with {@link AuthGrpcClient}.
     *
     * <p>Throws {@code BusinessException} as {@link AuthGrpcClient#getUser}: 404 {@code USER_NOT_FOUND}, 503
     * {@code AUTH_UNAVAILABLE}, 502 {@code AUTH_CALL_FAILED}.
     *
     * @param userId value of the {@code X-User-Id} header, checked by {@code AuthTestValidator}
     * @return the user with {@code transport} {@code gRPC} and the duration of the call
     */
    public AuthCallResult callOverGrpc(long userId) {
        return timed("gRPC", userId, authGrpcClient::getUser);
    }

    private static AuthCallResult timed(String transport, long userId, LongFunction<AuthUser> call) {
        long start = System.nanoTime();
        AuthUser user = call.apply(userId);
        return new AuthCallResult(transport, (System.nanoTime() - start) / 1_000_000, user);
    }
}
