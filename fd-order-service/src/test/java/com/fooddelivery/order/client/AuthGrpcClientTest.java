package com.fooddelivery.order.client;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.grpc.auth.v1.GetUserRequest;
import com.fooddelivery.grpc.auth.v1.UserResponse;
import com.fooddelivery.grpc.auth.v1.UserServiceGrpc;
import com.fooddelivery.order.config.AuthClientProperties;
import com.fooddelivery.web.exception.BusinessException;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Tests of {@link AuthGrpcClient} with {@link CallerServiceClientInterceptor}, against a fake {@code UserService} on
 * an in-process gRPC server (no port). Each test sets how the fake answers: a user, a status with an
 * {@code error-code} trailer, or no answer before the deadline.
 */
class AuthGrpcClientTest {

    private static final long USER_ID = 17939477151001L;
    private static final Duration TIMEOUT = Duration.ofMillis(300);

    private final AtomicReference<BiConsumer<GetUserRequest, StreamObserver<UserResponse>>> answer =
            new AtomicReference<>();
    private final AtomicReference<String> callerHeader = new AtomicReference<>();

    private Server server;
    private ManagedChannel channel;
    private AuthGrpcClient client;

    @BeforeEach
    void start() throws Exception {
        UserServiceGrpc.UserServiceImplBase fake = new UserServiceGrpc.UserServiceImplBase() {
            @Override
            public void getUser(GetUserRequest request, StreamObserver<UserResponse> responseObserver) {
                answer.get().accept(request, responseObserver);
            }
        };
        ServerInterceptor recordCaller = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                         Metadata headers,
                                                                         ServerCallHandler<ReqT, RespT> next) {
                callerHeader.set(headers.get(GrpcMetadataKeys.CALLER_SERVICE));
                return next.startCall(call, headers);
            }
        };

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .addService(ServerInterceptors.intercept(fake, recordCaller)).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        UserServiceGrpc.UserServiceBlockingStub stub = UserServiceGrpc.newBlockingStub(
                ClientInterceptors.intercept(channel, new CallerServiceClientInterceptor("service-order")));
        client = new AuthGrpcClient(stub, new AuthClientProperties(URI.create("http://unused"), TIMEOUT));
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void returnsTheUserAndSendsTheCallerService() {
        answer.set((request, observer) -> {
            observer.onNext(UserResponse.newBuilder().setId(request.getUserId()).setUsername("quan")
                    .setPhoneNumber("+84912345678").setRole("CUSTOMER").setActive(true).build());
            observer.onCompleted();
        });

        AuthUser user = client.getUser(USER_ID);

        assertThat(user).isEqualTo(new AuthUser(USER_ID, "quan", "+84912345678", "CUSTOMER", true));
        assertThat(callerHeader).hasValue("service-order");
    }

    @Test
    void unsetPhoneNumberBecomesNull() {
        answer.set((request, observer) -> {
            observer.onNext(UserResponse.newBuilder().setId(USER_ID).setUsername("quan").setRole("CUSTOMER").build());
            observer.onCompleted();
        });

        assertThat(client.getUser(USER_ID).phoneNumber()).isNull();
    }

    @Test
    void notFoundKeepsTheErrorCodeOfAuthService() {
        answer.set((request, observer) -> observer.onError(error(Status.NOT_FOUND, "USER_NOT_FOUND")));

        assertError(ApiStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    @Test
    void invalidArgumentBecomes400() {
        answer.set((request, observer) -> observer.onError(error(Status.INVALID_ARGUMENT, "INVALID_USER_ID")));

        assertError(ApiStatus.BAD_REQUEST, "INVALID_USER_ID");
    }

    @Test
    void noAnswerBeforeTheDeadlineBecomes503() {
        // Never answers: the client gives up after TIMEOUT instead of waiting forever
        answer.set((request, observer) -> { });

        long start = System.nanoTime();
        assertError(ApiStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(TIMEOUT.multipliedBy(5));
    }

    @Test
    void serverDownBecomes503() {
        server.shutdownNow();

        assertError(ApiStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE");
    }

    @Test
    void otherStatusBecomes502() {
        answer.set((request, observer) -> observer.onError(Status.INTERNAL.asRuntimeException()));

        assertError(ApiStatus.BAD_GATEWAY, "AUTH_CALL_FAILED");
    }

    private void assertError(ApiStatus status, String code) {
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> client.getUser(USER_ID));

        assertThat(e).isNotNull();
        assertThat(e.getStatus()).isEqualTo(status);
        assertThat(e.getCode()).isEqualTo(code);
    }

    // A failure as auth-service sends it: the gRPC status plus the business error code in the trailer
    private static Throwable error(Status status, String errorCode) {
        Metadata trailers = new Metadata();
        trailers.put(GrpcMetadataKeys.ERROR_CODE, errorCode);
        return status.withDescription(errorCode).asRuntimeException(trailers);
    }
}
