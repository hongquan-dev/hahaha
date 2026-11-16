package com.fooddelivery.grpc.support;

import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.grpc.auth.v1.GetUserRequest;
import com.fooddelivery.grpc.auth.v1.UserResponse;
import com.fooddelivery.grpc.auth.v1.UserServiceGrpc;
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
 * Tests of {@link CallerServiceClientInterceptor} on an in-process gRPC server that records the
 * {@link GrpcMetadataKeys#CALLER_SERVICE} header of each call.
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
        UserServiceGrpc.UserServiceBlockingStub stub = stubWith(new CallerServiceClientInterceptor("service-order"));

        stub.getUser(GetUserRequest.newBuilder().setUserId(1).build());

        assertThat(receivedCaller).hasValue("service-order");
    }

    private UserServiceGrpc.UserServiceBlockingStub stubWith(CallerServiceClientInterceptor interceptor)
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
        UserServiceGrpc.UserServiceImplBase service = new UserServiceGrpc.UserServiceImplBase() {
            @Override
            public void getUser(GetUserRequest request, StreamObserver<UserResponse> responseObserver) {
                responseObserver.onNext(UserResponse.newBuilder().setId(request.getUserId()).build());
                responseObserver.onCompleted();
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service, recorder)).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return UserServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel, interceptor));
    }
}
