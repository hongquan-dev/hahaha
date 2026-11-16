package com.fooddelivery.grpc.support;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.web.exception.BusinessException;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link BusinessExceptionGrpcHandler#handleException}: the gRPC status, description and
 * {@code error-code} trailer a client receives for each kind of exception.
 */
class BusinessExceptionGrpcHandlerTest {

    private final BusinessExceptionGrpcHandler handler = new BusinessExceptionGrpcHandler();

    @ParameterizedTest
    @CsvSource({
            "BAD_REQUEST,           INVALID_ARGUMENT",
            "UNPROCESSABLE_ENTITY,  INVALID_ARGUMENT",
            "UNAUTHORIZED,          UNAUTHENTICATED",
            "FORBIDDEN,             PERMISSION_DENIED",
            "NOT_FOUND,             NOT_FOUND",
            "CONFLICT,              ALREADY_EXISTS",
            "TOO_MANY_REQUESTS,     RESOURCE_EXHAUSTED",
            "SERVICE_UNAVAILABLE,   UNAVAILABLE",
            "GATEWAY_TIMEOUT,       DEADLINE_EXCEEDED",
            "INTERNAL_SERVER_ERROR, INTERNAL",
            "METHOD_NOT_ALLOWED,    INTERNAL",
            "BAD_GATEWAY,           INTERNAL"})
    void businessExceptionGetsTheMatchingCodeWithItsMessageAndErrorCode(ApiStatus status, Status.Code expected) {
        StatusException e = handler.handleException(new BusinessException(status, "SOME_ERROR", "Something failed"));

        assertThat(e.getStatus().getCode()).isEqualTo(expected);
        assertThat(e.getStatus().getDescription()).isEqualTo("Something failed");
        assertThat(e.getTrailers().get(GrpcMetadataKeys.ERROR_CODE)).isEqualTo("SOME_ERROR");
    }

    // Regression: a null code used to make Metadata.put throw NullPointerException. A null code means "the status'
    // default code", as in BusinessException and ResponseHandler
    @Test
    void businessExceptionWithoutCodeCarriesTheDefaultCodeOfItsStatus() {
        StatusException e = handler.handleException(new BusinessException(ApiStatus.NOT_FOUND, "User not found"));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(e.getStatus().getDescription()).isEqualTo("User not found");
        assertThat(e.getTrailers().get(GrpcMetadataKeys.ERROR_CODE)).isEqualTo("NOT_FOUND");
    }

    @Test
    void uncheckedExceptionGivesInternalWithoutDetailsOrErrorCode() {
        assertInternalWithoutDetails(handler.handleException(new IllegalStateException("database password is wrong")));
    }

    @Test
    void checkedExceptionGivesInternalWithoutDetailsOrErrorCode() {
        assertInternalWithoutDetails(handler.handleException(new SQLException("relation users does not exist")));
    }

    private static void assertInternalWithoutDetails(StatusException e) {
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(e.getStatus().getDescription()).isEqualTo("Internal error");
        Metadata trailers = e.getTrailers();
        assertThat(trailers == null ? null : trailers.get(GrpcMetadataKeys.ERROR_CODE)).isNull();
    }
}
