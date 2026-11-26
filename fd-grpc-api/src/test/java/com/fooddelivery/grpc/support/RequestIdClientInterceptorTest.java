package com.fooddelivery.grpc.support;

import com.fooddelivery.dto.requestid.RequestIds;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.grpc.test.v1.EchoRequest;
import com.fooddelivery.grpc.test.v1.EchoResponse;
import com.fooddelivery.grpc.test.v1.EchoServiceGrpc;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of {@link RequestIdClientInterceptor} on an in-process gRPC server of the test-only {@code EchoService}: what
 * {@code x-request-id} header reaches the server for each request ID of the calling thread, and, with
 * {@link RequestIdServerInterceptor} on the server, that the called service handles the call under the same ID.
 */
class RequestIdClientInterceptorTest {

    private static final String REQUEST_ID = "3f2a9c1e-7b4d-4e0a-9f1c-2d6e8b5a7c30";

    private final AtomicReference<String> receivedHeader = new AtomicReference<>();
    private final AtomicReference<String> idWhileHandling = new AtomicReference<>();
    private Server server;
    private ManagedChannel channel;
    private EchoServiceGrpc.EchoServiceBlockingStub stub;

    @BeforeEach
    void start() throws Exception {
        EchoServiceGrpc.EchoServiceImplBase service = new EchoServiceGrpc.EchoServiceImplBase() {
            @Override
            public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
                idWhileHandling.set(ThreadContext.get(RequestIds.THREAD_CONTEXT_KEY));
                responseObserver.onNext(EchoResponse.newBuilder().setText(request.getText()).build());
                responseObserver.onCompleted();
            }
        };
        ServerInterceptor recorder = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                         Metadata headers,
                                                                         ServerCallHandler<ReqT, RespT> next) {
                receivedHeader.set(headers.get(GrpcMetadataKeys.REQUEST_ID));
                return next.startCall(call, headers);
            }
        };
        String name = InProcessServerBuilder.generateName();
        // The recorder runs first and sees the header as sent; the request ID interceptor then handles it
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service, new RequestIdServerInterceptor(), recorder))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = EchoServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel, new RequestIdClientInterceptor()));
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
        ThreadContext.clearMap();
    }

    @Test
    void idOfTheCallingThreadIsSentAndUsedByTheCalledService() {
        ThreadContext.put(RequestIds.THREAD_CONTEXT_KEY, REQUEST_ID);

        stub.echo(EchoRequest.getDefaultInstance());

        assertThat(receivedHeader).hasValue(REQUEST_ID);
        assertThat(idWhileHandling).hasValue(REQUEST_ID);
    }

    @Test
    void threadWithoutIdSendsNoHeader() {
        stub.echo(EchoRequest.getDefaultInstance());

        assertThat(receivedHeader.get()).isNull();
    }

    @Test
    void invalidIdOfTheThreadIsNotSent() {
        ThreadContext.put(RequestIds.THREAD_CONTEXT_KEY, "has space");

        stub.echo(EchoRequest.getDefaultInstance());

        assertThat(receivedHeader.get()).isNull();
    }
}
