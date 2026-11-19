package com.fooddelivery.order.client;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.grpc.auth.v1.GetUserRequest;
import com.fooddelivery.grpc.auth.v1.UserResponse;
import com.fooddelivery.grpc.auth.v1.UserServiceGrpc;
import com.fooddelivery.order.config.AuthClientProperties;
import com.fooddelivery.web.exception.BusinessException;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Calls the gRPC {@code fooddelivery.auth.v1.UserService} of auth-service through the stub created by
 * {@code GrpcClientConfig}, and returns the user as an {@link AuthUser}.
 *
 * <p>Wraps the generated stub so the rest of order-service never sees protobuf classes or gRPC statuses. Every call
 * has a deadline of {@code services.auth.timeout}. Failures become a {@code BusinessException}:
 * <ul>
 *   <li>{@code NOT_FOUND} / {@code INVALID_ARGUMENT}: 404 / 400 with the error code of the {@code error-code} trailer,
 *       e.g. {@code USER_NOT_FOUND}, as thrown inside auth-service.</li>
 *   <li>{@code UNAVAILABLE} (auth-service down) or {@code DEADLINE_EXCEEDED} (too slow): 503
 *       {@code AUTH_UNAVAILABLE}.</li>
 *   <li>Any other status: 502 {@code AUTH_CALL_FAILED}.</li>
 * </ul>
 */
@Component
public class AuthGrpcClient {
    private static final Logger log = LogManager.getLogger(AuthGrpcClient.class);

    private final UserServiceGrpc.UserServiceBlockingStub stub;
    private final Duration timeout;

    public AuthGrpcClient(UserServiceGrpc.UserServiceBlockingStub stub, AuthClientProperties properties) {
        this.stub = stub;
        this.timeout = properties.timeout();
    }

    /**
     * Fetches a user from auth-service over gRPC.
     *
     * <p>Throws {@code BusinessException} 404 {@code USER_NOT_FOUND} or 400 {@code INVALID_USER_ID} as answered by
     * auth-service, 503 {@code AUTH_UNAVAILABLE} if it cannot be reached before the deadline, 502
     * {@code AUTH_CALL_FAILED} otherwise.
     *
     * @param userId ID taken from the {@code X-User-Id} header set by the gateway
     * @return the user, active or locked
     */
    public AuthUser getUser(long userId) {
        try {
            // The deadline travels with the call (grpc-timeout header), so auth-service also knows when to give up
            UserResponse response = stub.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .getUser(GetUserRequest.newBuilder().setUserId(userId).build());
            return new AuthUser(response.getId(), response.getUsername(),
                    response.hasPhoneNumber() ? response.getPhoneNumber() : null, response.getRole(),
                    response.getActive());
        } catch (StatusRuntimeException e) {
            throw toBusinessException(e, userId);
        }
    }

    private static BusinessException toBusinessException(StatusRuntimeException e, long userId) {
        Status.Code code = e.getStatus().getCode();
        Metadata trailers = e.getTrailers();
        String errorCode = trailers == null ? null : trailers.get(GrpcMetadataKeys.ERROR_CODE);
        String message = e.getStatus().getDescription();

        return switch (code) {
            case NOT_FOUND -> businessError(ApiStatus.NOT_FOUND, errorCode, message, "USER_NOT_FOUND");
            case INVALID_ARGUMENT -> businessError(ApiStatus.BAD_REQUEST, errorCode, message, "INVALID_USER_ID");
            case UNAVAILABLE, DEADLINE_EXCEEDED -> {
                log.warn("[AUTH-gRPC] auth-service unreachable for userId={}: {}", userId, e.getStatus());
                yield new BusinessException(ApiStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE",
                        "Authentication service is unavailable");
            }
            default -> {
                log.warn("[AUTH-gRPC] auth-service answered {} for userId={}", e.getStatus(), userId);
                yield new BusinessException(ApiStatus.BAD_GATEWAY, "AUTH_CALL_FAILED",
                        "Authentication service call failed");
            }
        };
    }

    // The error code and message of auth-service when present; the defaults only if its trailer is missing
    private static BusinessException businessError(ApiStatus status, String errorCode, String message,
                                                   String defaultCode) {
        return new BusinessException(status, errorCode != null ? errorCode : defaultCode,
                message != null ? message : status.name());
    }
}
