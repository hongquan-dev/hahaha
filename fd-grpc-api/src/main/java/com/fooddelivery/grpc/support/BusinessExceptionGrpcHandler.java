package com.fooddelivery.grpc.support;

import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.web.exception.BusinessException;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;

/**
 * The gRPC counterpart of {@code GlobalExceptionHandler}: turns an exception thrown by a gRPC service of the
 * application into the status the client receives. Registered by {@link GrpcSupportAutoConfiguration} in every service
 * that runs a gRPC server; Spring gRPC calls every {@link GrpcExceptionHandler} bean at request time, for any service.
 *
 * <ul>
 *   <li>{@code BusinessException}: the gRPC code matching its HTTP status in {@link GrpcStatuses} (e.g. 404 ->
 *       {@code NOT_FOUND}, read back by {@link GrpcClientErrors} on the client), its message
 *       as description, and its error code in the {@link GrpcMetadataKeys#ERROR_CODE} trailer, e.g.
 *       {@code USER_NOT_FOUND}, so the client can tell errors apart as with the HTTP API. Without a code, the default
 *       code of the status, e.g. {@code NOT_FOUND}.</li>
 *   <li>Anything else: {@code INTERNAL} with a generic description; the exception is only logged, so no internal
 *       detail reaches the client.</li>
 * </ul>
 */
public class BusinessExceptionGrpcHandler implements GrpcExceptionHandler {
    private static final Logger log = LogManager.getLogger(BusinessExceptionGrpcHandler.class);

    @Override
    public StatusException handleException(Throwable exception) {
        if (exception instanceof BusinessException e) {
            // No code means the status' default code, as in ResponseHandler, e.g. NOT_FOUND
            String code = e.getCode() == null || e.getCode().isBlank() ? e.getStatus().code() : e.getCode();
            Metadata trailers = new Metadata();
            trailers.put(GrpcMetadataKeys.ERROR_CODE, code);
            return Status.fromCode(GrpcStatuses.toGrpc(e.getStatus())).withDescription(e.getMessage())
                    .asException(trailers);
        }
        log.error("[gRPC] Unexpected error", exception);
        return Status.INTERNAL.withDescription("Internal error").asException();
    }
}
