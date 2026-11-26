package com.fooddelivery.grpc.support;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.grpc.test.v1.EchoRequest;
import com.fooddelivery.grpc.test.v1.EchoResponse;
import com.fooddelivery.grpc.test.v1.EchoServiceGrpc;
import com.fooddelivery.web.exception.BusinessException;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Unit tests of {@link GrpcClientErrors#toBusinessException}: business errors keep their status, code and message,
 * availability errors become 503 {@code <TARGET>_UNAVAILABLE}, the rest 502 {@code <TARGET>_CALL_FAILED}. One test
 * makes a real in-process call through {@link BusinessExceptionGrpcHandler} to check the round trip.
 */
class GrpcClientErrorsTest {

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
    void businessErrorKeepsStatusCodeAndMessage(Status.Code code, ApiStatus expected) {
        BusinessException e = GrpcClientErrors.toBusinessException(
                failure(code, "Restaurant 17000000001002 not found", "RESTAURANT_NOT_FOUND"), "RESTAURANT");

        assertThat(e.getStatus()).isEqualTo(expected);
        assertThat(e.getCode()).isEqualTo("RESTAURANT_NOT_FOUND");
        assertThat(e.getMessage()).isEqualTo("Restaurant 17000000001002 not found");
    }

    @Test
    void businessErrorWithoutDescriptionGetsTheReasonPhrase() {
        BusinessException e = GrpcClientErrors.toBusinessException(
                failure(Status.Code.NOT_FOUND, null, "RESTAURANT_NOT_FOUND"), "RESTAURANT");

        assertThat(e.getStatus()).isEqualTo(ApiStatus.NOT_FOUND);
        assertThat(e.getMessage()).isEqualTo(ApiStatus.NOT_FOUND.reasonPhrase());
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
    void unreachableServiceGivesUnavailable(Status.Code code) {
        BusinessException e = GrpcClientErrors.toBusinessException(failure(code, "io exception", null), "AUTH");

        assertThat(e.getStatus()).isEqualTo(ApiStatus.SERVICE_UNAVAILABLE);
        assertThat(e.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"NOT_FOUND", "INTERNAL", "UNKNOWN", "CANCELLED", "UNIMPLEMENTED"})
    void otherFailureWithoutErrorCodeGivesCallFailed(Status.Code code) {
        BusinessException e = GrpcClientErrors.toBusinessException(failure(code, "Internal error", null), "AUTH");

        assertThat(e.getStatus()).isEqualTo(ApiStatus.BAD_GATEWAY);
        assertThat(e.getCode()).isEqualTo("AUTH_CALL_FAILED");
    }

    @Test
    void errorCodeOnInternalStillGivesCallFailed() {
        BusinessException e = GrpcClientErrors.toBusinessException(
                failure(Status.Code.INTERNAL, "Something broke", "SOME_ERROR"), "AUTH");

        assertThat(e.getStatus()).isEqualTo(ApiStatus.BAD_GATEWAY);
        assertThat(e.getCode()).isEqualTo("AUTH_CALL_FAILED");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "auth", "Auth", "1AUTH", "AUTH-SERVICE"})
    void targetThatIsNotUpperCaseIsRejected(String target) {
        StatusRuntimeException failure = failure(Status.Code.UNAVAILABLE, null, null);

        assertThatIllegalArgumentException().isThrownBy(() -> GrpcClientErrors.toBusinessException(failure, target));
    }

    @Test
    void businessExceptionOfTheServerComesBackUnchangedOverARealCall() throws Exception {
        String name = InProcessServerBuilder.generateName();
        EchoServiceGrpc.EchoServiceImplBase service = new EchoServiceGrpc.EchoServiceImplBase() {
            @Override
            public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
                throw new BusinessException(ApiStatus.CONFLICT, "ORDER_ALREADY_PAID", "Order is already paid");
            }
        };
        Server server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service,
                        new GrpcExceptionHandlerInterceptor(new BusinessExceptionGrpcHandler())))
                .build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            StatusRuntimeException failure = catchThrowableOfType(StatusRuntimeException.class,
                    () -> EchoServiceGrpc.newBlockingStub(channel).echo(EchoRequest.getDefaultInstance()));

            BusinessException e = GrpcClientErrors.toBusinessException(failure, "ORDER");

            assertThat(e.getStatus()).isEqualTo(ApiStatus.CONFLICT);
            assertThat(e.getCode()).isEqualTo("ORDER_ALREADY_PAID");
            assertThat(e.getMessage()).isEqualTo("Order is already paid");
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    // What a stub throws for a call that ended with this code, description and error-code trailer (null: none)
    private static StatusRuntimeException failure(Status.Code code, String description, String errorCode) {
        Metadata trailers = new Metadata();
        if (errorCode != null) {
            trailers.put(GrpcMetadataKeys.ERROR_CODE, errorCode);
        }
        return Status.fromCode(code).withDescription(description).asRuntimeException(trailers);
    }
}
