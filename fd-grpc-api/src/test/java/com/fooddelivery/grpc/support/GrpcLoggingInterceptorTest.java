package com.fooddelivery.grpc.support;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.grpc.test.v1.EchoRequest;
import com.fooddelivery.grpc.test.v1.EchoResponse;
import com.fooddelivery.grpc.test.v1.EchoServiceGrpc;
import com.fooddelivery.grpc.testing.InProcessGrpcServer;
import com.fooddelivery.web.exception.BusinessException;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Tests that {@link GrpcLoggingInterceptor} is transparent: on {@link InProcessGrpcServer}, where it wraps the
 * exception interceptor as its {@code @Order} places it in a running service, calls still complete with the same
 * response, status and {@code error-code} trailer, with or without the caller header. The service is the test-only {@code EchoService}
 * whose answer each test sets. The log line itself is not checked.
 */
class GrpcLoggingInterceptorTest {

    // What the fake service does with the text it receives: return a response or throw
    private Function<String, EchoResponse> answer;
    private InProcessGrpcServer server;
    private EchoServiceGrpc.EchoServiceBlockingStub stub;

    @BeforeEach
    void start() {
        EchoServiceGrpc.EchoServiceImplBase service = new EchoServiceGrpc.EchoServiceImplBase() {
            @Override
            public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
                responseObserver.onNext(answer.apply(request.getText()));
                responseObserver.onCompleted();
            }
        };
        // Same interceptors and order as in a running service, the logging one outside the exception one
        server = InProcessGrpcServer.start(service);
        stub = EchoServiceGrpc.newBlockingStub(server.channel());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void successfulCallReturnsTheResponse() {
        answer = text -> EchoResponse.newBuilder().setText(text).build();

        EchoResponse response = withCaller("service-order").echo(request("hello"));

        assertThat(response.getText()).isEqualTo("hello");
    }

    @Test
    void callWithoutCallerHeaderStillCompletes() {
        answer = text -> EchoResponse.newBuilder().setText(text).build();

        assertThat(stub.echo(request("hello")).getText()).isEqualTo("hello");
    }

    @Test
    void businessErrorKeepsItsStatusAndErrorCode() {
        answer = text -> {
            throw new BusinessException(ApiStatus.NOT_FOUND, "USER_NOT_FOUND", "User " + text + " not found");
        };

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> withCaller("service-order").echo(request("17939477151001")));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(e.getTrailers().get(GrpcMetadataKeys.ERROR_CODE)).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void unexpectedErrorKeepsInternalStatus() {
        answer = text -> {
            throw new IllegalStateException("database is down");
        };

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> stub.echo(request("hello")));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(e.getStatus().getDescription()).isEqualTo("Internal error");
    }

    private EchoServiceGrpc.EchoServiceBlockingStub withCaller(String caller) {
        Metadata headers = new Metadata();
        headers.put(GrpcMetadataKeys.CALLER_SERVICE, caller);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static EchoRequest request(String text) {
        return EchoRequest.newBuilder().setText(text).build();
    }
}
