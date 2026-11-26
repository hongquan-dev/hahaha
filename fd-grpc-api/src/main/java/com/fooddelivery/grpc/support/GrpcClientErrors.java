package com.fooddelivery.grpc.support;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.web.exception.BusinessException;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.regex.Pattern;

/**
 * Turns a failed gRPC call into the {@code BusinessException} the calling service throws: the client-side counterpart
 * of {@link BusinessExceptionGrpcHandler}. Every {@code XxxGrpcClient} calls it at request time when its stub throws,
 * so all clients report errors the same way.
 *
 * <ul>
 *   <li>Business error of the called service (an {@link GrpcMetadataKeys#ERROR_CODE} trailer and a code that
 *       {@link GrpcStatuses#toApiStatus} knows): the same HTTP status, error code and message, e.g. {@code NOT_FOUND}
 *       + {@code USER_NOT_FOUND} -> 404 {@code USER_NOT_FOUND}, as if the client had called the service directly.</li>
 *   <li>{@code UNAVAILABLE} (service down) or {@code DEADLINE_EXCEEDED} (too slow) without trailer: 503
 *       {@code <TARGET>_UNAVAILABLE}.</li>
 *   <li>Anything else, e.g. {@code INTERNAL}: 502 {@code <TARGET>_CALL_FAILED}, without the details of the called
 *       service.</li>
 * </ul>
 *
 * <p>Field errors ({@code BusinessException.getErrors()}) do not travel over gRPC: only the code and the message do.
 */
public final class GrpcClientErrors {
    private static final Logger log = LogManager.getLogger(GrpcClientErrors.class);

    // Name of the called service in the error codes, e.g. "AUTH" -> "AUTH_UNAVAILABLE"
    private static final Pattern TARGET = Pattern.compile("[A-Z][A-Z0-9_]*");

    private GrpcClientErrors() {
    }

    /**
     * The {@code BusinessException} for a failed call to another service.
     *
     * <p>Throws {@code IllegalArgumentException} if {@code target} is not upper case, a programming error of the
     * client rather than a failure of the call.
     *
     * @param e      what the stub threw
     * @param target upper-case name of the called service used in the error codes, e.g. {@code "AUTH"} ->
     *               {@code AUTH_UNAVAILABLE}, {@code AUTH_CALL_FAILED}
     * @return the exception to throw, never {@code null}
     */
    public static BusinessException toBusinessException(StatusRuntimeException e, String target) {
        if (target == null || !TARGET.matcher(target).matches()) {
            throw new IllegalArgumentException("target must be upper case, e.g. \"AUTH\", got: " + target);
        }
        Status status = e.getStatus();
        Metadata trailers = e.getTrailers();
        String errorCode = trailers == null ? null : trailers.get(GrpcMetadataKeys.ERROR_CODE);
        ApiStatus apiStatus = GrpcStatuses.toApiStatus(status.getCode());

        if (errorCode != null && apiStatus != null) {
            String message = status.getDescription() != null ? status.getDescription() : apiStatus.reasonPhrase();
            return new BusinessException(apiStatus, errorCode, message);
        }
        if (status.getCode() == Status.Code.UNAVAILABLE || status.getCode() == Status.Code.DEADLINE_EXCEEDED) {
            log.warn("[gRPC-CLIENT] {} unreachable: {}", target, status);
            return new BusinessException(ApiStatus.SERVICE_UNAVAILABLE, target + "_UNAVAILABLE",
                    "Service " + target + " is unavailable");
        }
        log.warn("[gRPC-CLIENT] {} answered {}", target, status);
        return new BusinessException(ApiStatus.BAD_GATEWAY, target + "_CALL_FAILED",
                "Call to service " + target + " failed");
    }
}
