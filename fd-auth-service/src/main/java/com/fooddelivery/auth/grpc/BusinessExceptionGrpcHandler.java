package com.fooddelivery.auth.grpc;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.web.exception.BusinessException;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

/**
 * The gRPC counterpart of {@code GlobalExceptionHandler}: turns an exception thrown by a gRPC service of this
 * application into the status the client receives. Spring gRPC calls every {@link GrpcExceptionHandler} bean at
 * request time, for any service.
 *
 * <ul>
 *   <li>{@code BusinessException}: the gRPC code matching its HTTP status (e.g. 404 -> {@code NOT_FOUND}), its message
 *       as description, and its error code in the {@link #ERROR_CODE} metadata, e.g. {@code USER_NOT_FOUND}, so the
 *       client can tell errors apart as with the HTTP API.</li>
 *   <li>Anything else: {@code INTERNAL} with a generic description; the exception is only logged, so no internal
 *       detail reaches the client.</li>
 * </ul>
 */
@Component
public class BusinessExceptionGrpcHandler implements GrpcExceptionHandler {
    private static final Logger log = LogManager.getLogger(BusinessExceptionGrpcHandler.class);

    // Trailer with the error code of BusinessException, e.g. "USER_NOT_FOUND"; read by clients from
    // StatusRuntimeException.getTrailers()
    public static final Metadata.Key<String> ERROR_CODE =
            Metadata.Key.of("error-code", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public StatusException handleException(Throwable exception) {
        if (exception instanceof BusinessException e) {
            Metadata trailers = new Metadata();
            trailers.put(ERROR_CODE, e.getCode());
            return toGrpcStatus(e.getStatus()).withDescription(e.getMessage()).asException(trailers);
        }
        log.error("[gRPC] Unexpected error", exception);
        return Status.INTERNAL.withDescription("Internal error").asException();
    }

    // Closest gRPC code for each HTTP status a BusinessException can carry; INTERNAL for the rest
    static Status toGrpcStatus(ApiStatus status) {
        return switch (status) {
            case BAD_REQUEST, UNPROCESSABLE_ENTITY -> Status.INVALID_ARGUMENT;
            case UNAUTHORIZED -> Status.UNAUTHENTICATED;
            case FORBIDDEN -> Status.PERMISSION_DENIED;
            case NOT_FOUND -> Status.NOT_FOUND;
            case CONFLICT -> Status.ALREADY_EXISTS;
            case TOO_MANY_REQUESTS -> Status.RESOURCE_EXHAUSTED;
            case SERVICE_UNAVAILABLE -> Status.UNAVAILABLE;
            case GATEWAY_TIMEOUT -> Status.DEADLINE_EXCEEDED;
            default -> Status.INTERNAL;
        };
    }
}
