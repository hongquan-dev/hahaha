package com.fooddelivery.grpc.support;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of {@link CallerServiceClientInterceptor} on an in-process gRPC server of the test-only {@code EchoService}
 * that records the {@link GrpcMetadataKeys#CALLER_SERVICE} header of each call.
 */
class CallerServiceClientInterceptorTest {

    private final AtomicReference<String> receivedCaller = new AtomicReference<>();
    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void everyCallCarriesTheApplicationName() throws Exception {
        EchoServiceGrpc.EchoServiceBlockingStub stub = stubWith(new CallerServiceClientInterceptor("service-order"));

        stub.echo(EchoRequest.newBuilder().setText("hello").build());

        assertThat(receivedCaller).hasValue("service-order");
    }

    private EchoServiceGrpc.EchoServiceBlockingStub stubWith(CallerServiceClientInterceptor interceptor)
            throws Exception {
        String name = InProcessServerBuilder.generateName();
        ServerInterceptor recorder = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                         Metadata headers,
                                                                         ServerCallHandler<ReqT, RespT> next) {
                receivedCaller.set(headers.get(GrpcMetadataKeys.CALLER_SERVICE));
                return next.startCall(call, headers);
            }
        };
        EchoServiceGrpc.EchoServiceImplBase service = new EchoServiceGrpc.EchoServiceImplBase() {
            @Override
            public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
                responseObserver.onNext(EchoResponse.newBuilder().setText(request.getText()).build());
                responseObserver.onCompleted();
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service, recorder)).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return EchoServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel, interceptor));
    }
}
