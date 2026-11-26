package com.fooddelivery.grpc.support;

import com.fooddelivery.dto.response.ApiStatus;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of the {@link GrpcStatuses} table in both directions, and that reading it back gives the original HTTP
 * status for every status the server maps to a code other than {@code INTERNAL}.
 */
class GrpcStatusesTest {

    @ParameterizedTest
    @CsvSource({
            "INVALID_ARGUMENT,   BAD_REQUEST",
            "UNAUTHENTICATED,    UNAUTHORIZED",
            "PERMISSION_DENIED,  FORBIDDEN",
            "NOT_FOUND,          NOT_FOUND",
            "ALREADY_EXISTS,     CONFLICT",
            "RESOURCE_EXHAUSTED, TOO_MANY_REQUESTS",
            "UNAVAILABLE,        SERVICE_UNAVAILABLE",
            "DEADLINE_EXCEEDED,  GATEWAY_TIMEOUT"})
    void knownCodeGivesItsHttpStatus(Status.Code code, ApiStatus expected) {
        assertThat(GrpcStatuses.toApiStatus(code)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"OK", "CANCELLED", "UNKNOWN", "INTERNAL", "UNIMPLEMENTED",
            "DATA_LOSS", "FAILED_PRECONDITION", "ABORTED", "OUT_OF_RANGE"})
    void codeTheServerNeverSendsForAClientErrorGivesNull(Status.Code code) {
        assertThat(GrpcStatuses.toApiStatus(code)).isNull();
    }

    @ParameterizedTest
    @EnumSource(ApiStatus.class)
    void everyMappedStatusComesBackAsItself(ApiStatus status) {
        Status.Code code = GrpcStatuses.toGrpc(status);

        if (code == Status.Code.INTERNAL) {
            assertThat(GrpcStatuses.toApiStatus(code)).isNull();
        } else if (status == ApiStatus.UNPROCESSABLE_ENTITY) {
            assertThat(GrpcStatuses.toApiStatus(code)).isEqualTo(ApiStatus.BAD_REQUEST);
        } else {
            assertThat(GrpcStatuses.toApiStatus(code)).isEqualTo(status);
        }
    }

    @Test
    void unprocessableEntitySharesInvalidArgumentWithBadRequest() {
        assertThat(GrpcStatuses.toGrpc(ApiStatus.UNPROCESSABLE_ENTITY)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
}
