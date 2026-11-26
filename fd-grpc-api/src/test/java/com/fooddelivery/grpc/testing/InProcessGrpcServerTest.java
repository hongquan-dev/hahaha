package com.fooddelivery.grpc.testing;

import com.fooddelivery.dto.requestid.RequestIds;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.grpc.test.v1.EchoRequest;
import com.fooddelivery.grpc.test.v1.EchoResponse;
import com.fooddelivery.grpc.test.v1.EchoServiceGrpc;
import com.fooddelivery.web.exception.BusinessException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Tests of {@link InProcessGrpcServer} with the test-only {@code EchoService}: a call goes through the interceptors of a
 * running service (business error -> status + {@code error-code}, request ID in the {@code ThreadContext}), and
 * stopping the server makes calls fail as when the called service is down.
 */
class InProcessGrpcServerTest {

    private final AtomicReference<String> idWhileHandling = new AtomicReference<>();

    @Test
    void answerOfTheServiceReachesTheStub() {
        try (InProcessGrpcServer server = InProcessGrpcServer.start(echo(text -> text))) {
            EchoResponse response = EchoServiceGrpc.newBlockingStub(server.channel())
                    .echo(EchoRequest.newBuilder().setText("hello").build());

            assertThat(response.getText()).isEqualTo("hello");
            assertThat(RequestIds.isValid(idWhileHandling.get())).isTrue();
        }
    }

    @Test
    void businessExceptionBecomesItsStatusAndErrorCode() {
        try (InProcessGrpcServer server = InProcessGrpcServer.start(echo(text -> {
            throw new BusinessException(ApiStatus.NOT_FOUND, "RESTAURANT_NOT_FOUND", "Restaurant not found");
        }))) {
            StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                    () -> EchoServiceGrpc.newBlockingStub(server.channel()).echo(EchoRequest.getDefaultInstance()));

            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
            assertThat(e.getTrailers().get(GrpcMetadataKeys.ERROR_CODE)).isEqualTo("RESTAURANT_NOT_FOUND");
        }
    }

    @Test
    void stoppedServerGivesUnavailable() {
        try (InProcessGrpcServer server = InProcessGrpcServer.start(echo(text -> text))) {
            server.stopServer();

            StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                    () -> EchoServiceGrpc.newBlockingStub(server.channel()).echo(EchoRequest.getDefaultInstance()));

            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        }
    }

    // EchoService that records the request ID while handling and answers with the function of the text
    private EchoServiceGrpc.EchoServiceImplBase echo(Function<String, String> answer) {
        return new EchoServiceGrpc.EchoServiceImplBase() {
            @Override
            public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
                idWhileHandling.set(ThreadContext.get(RequestIds.THREAD_CONTEXT_KEY));
                responseObserver.onNext(EchoResponse.newBuilder().setText(answer.apply(request.getText())).build());
                responseObserver.onCompleted();
            }
        };
    }
}
