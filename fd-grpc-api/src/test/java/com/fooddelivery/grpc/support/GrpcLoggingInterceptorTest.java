package com.fooddelivery.grpc.support;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.grpc.auth.v1.GetUserRequest;
import com.fooddelivery.grpc.auth.v1.UserResponse;
import com.fooddelivery.grpc.auth.v1.UserServiceGrpc;
import com.fooddelivery.web.exception.BusinessException;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;

import java.util.function.LongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Tests that {@link GrpcLoggingInterceptor} is transparent: on an in-process gRPC server where it wraps the exception
 * interceptor, as its {@code @Order} places it, calls still complete with the same response, status and
 * {@code error-code} trailer, with or without the caller header. The service is a fake {@code UserService} whose
 * answer each test sets. The log line itself is not checked.
 */
class GrpcLoggingInterceptorTest {

    private static final long USER_ID = 17939477151001L;

    // What the fake service does for a user ID: return a response or throw
    private LongFunction<UserResponse> answer;
    private Server server;
    private ManagedChannel channel;
    private UserServiceGrpc.UserServiceBlockingStub stub;

    @BeforeEach
    void start() throws Exception {
        String name = InProcessServerBuilder.generateName();
        UserServiceGrpc.UserServiceImplBase service = new UserServiceGrpc.UserServiceImplBase() {
            @Override
            public void getUser(GetUserRequest request, StreamObserver<UserResponse> responseObserver) {
                responseObserver.onNext(answer.apply(request.getUserId()));
                responseObserver.onCompleted();
            }
        };
        // The last interceptor runs first, so the logging interceptor is outside the exception interceptor
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service,
                        new GrpcExceptionHandlerInterceptor(new BusinessExceptionGrpcHandler()),
                        new GrpcLoggingInterceptor()))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = UserServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void successfulCallReturnsTheResponse() {
        answer = id -> UserResponse.newBuilder().setId(id).setUsername("quan").build();

        UserResponse response = withCaller("service-order").getUser(request());

        assertThat(response.getId()).isEqualTo(USER_ID);
        assertThat(response.getUsername()).isEqualTo("quan");
    }

    @Test
    void callWithoutCallerHeaderStillCompletes() {
        answer = id -> UserResponse.newBuilder().setId(id).build();

        assertThat(stub.getUser(request()).getId()).isEqualTo(USER_ID);
    }

    @Test
    void businessErrorKeepsItsStatusAndErrorCode() {
        answer = id -> {
            throw new BusinessException(ApiStatus.NOT_FOUND, "USER_NOT_FOUND", "User " + id + " not found");
        };

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> withCaller("service-order").getUser(request()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(e.getTrailers().get(GrpcMetadataKeys.ERROR_CODE)).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void unexpectedErrorKeepsInternalStatus() {
        answer = id -> {
            throw new IllegalStateException("database is down");
        };

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class, () -> stub.getUser(request()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(e.getStatus().getDescription()).isEqualTo("Internal error");
    }

    private UserServiceGrpc.UserServiceBlockingStub withCaller(String caller) {
        Metadata headers = new Metadata();
        headers.put(GrpcMetadataKeys.CALLER_SERVICE, caller);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static GetUserRequest request() {
        return GetUserRequest.newBuilder().setUserId(USER_ID).build();
    }
}
