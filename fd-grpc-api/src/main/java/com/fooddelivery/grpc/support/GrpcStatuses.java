package com.fooddelivery.grpc.support;

import com.fooddelivery.dto.response.ApiStatus;
import io.grpc.Status;

/**
 * The one table between the HTTP statuses of {@code BusinessException} and the gRPC status codes, read in both
 * directions: by {@link BusinessExceptionGrpcHandler} on the server and by {@link GrpcClientErrors} on the client, so
 * an error keeps its status across a call.
 *
 * <p>The mapping is lossy in one place: 400 and 422 both become {@code INVALID_ARGUMENT}, which comes back as 400.
 */
public final class GrpcStatuses {

    private GrpcStatuses() {
    }

    // Closest gRPC code for each HTTP status a BusinessException can carry; INTERNAL for the rest
    public static Status.Code toGrpc(ApiStatus status) {
        return switch (status) {
            case BAD_REQUEST, UNPROCESSABLE_ENTITY -> Status.Code.INVALID_ARGUMENT;
            case UNAUTHORIZED -> Status.Code.UNAUTHENTICATED;
            case FORBIDDEN -> Status.Code.PERMISSION_DENIED;
            case NOT_FOUND -> Status.Code.NOT_FOUND;
            case CONFLICT -> Status.Code.ALREADY_EXISTS;
            case TOO_MANY_REQUESTS -> Status.Code.RESOURCE_EXHAUSTED;
            case SERVICE_UNAVAILABLE -> Status.Code.UNAVAILABLE;
            case GATEWAY_TIMEOUT -> Status.Code.DEADLINE_EXCEEDED;
            default -> Status.Code.INTERNAL;
        };
    }

    // HTTP status of a gRPC code that toGrpc produces from a client error or an availability error, e.g. NOT_FOUND ->
    // 404; null for INTERNAL and the codes toGrpc never produces, e.g. CANCELLED
    public static ApiStatus toApiStatus(Status.Code code) {
        return switch (code) {
            case INVALID_ARGUMENT -> ApiStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> ApiStatus.UNAUTHORIZED;
            case PERMISSION_DENIED -> ApiStatus.FORBIDDEN;
            case NOT_FOUND -> ApiStatus.NOT_FOUND;
            case ALREADY_EXISTS -> ApiStatus.CONFLICT;
            case RESOURCE_EXHAUSTED -> ApiStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE -> ApiStatus.SERVICE_UNAVAILABLE;
            case DEADLINE_EXCEEDED -> ApiStatus.GATEWAY_TIMEOUT;
            default -> null;
        };
    }
}
