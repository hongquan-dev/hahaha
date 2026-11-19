package com.fooddelivery.auth.grpc;

import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.repository.UserRepository;
import com.fooddelivery.auth.service.user.UserQueryService;
import com.fooddelivery.grpc.support.BusinessExceptionGrpcHandler;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.grpc.auth.v1.GetUserRequest;
import com.fooddelivery.grpc.auth.v1.UserResponse;
import com.fooddelivery.grpc.auth.v1.UserServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests of {@link UserGrpcService} through a real client stub, on an in-process gRPC server (no port) with the same
 * exception interceptor Spring gRPC installs, wired to {@link BusinessExceptionGrpcHandler}. The repository is mocked.
 */
class UserGrpcServiceTest {

    private static final long USER_ID = 17939477151001L;

    private final UserRepository userRepository = mock(UserRepository.class);
    private Server server;
    private ManagedChannel channel;
    private UserServiceGrpc.UserServiceBlockingStub stub;

    @BeforeEach
    void start() throws Exception {
        String name = InProcessServerBuilder.generateName();
        UserGrpcService service = new UserGrpcService(new UserQueryService(userRepository));
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service,
                        new GrpcExceptionHandlerInterceptor(new BusinessExceptionGrpcHandler())))
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
    void returnsTheUserWithThisId() {
        User user = new User(USER_ID, "quan", "$2a$10$hash", "quan@test.com", "+84912345678", "CUSTOMER");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        UserResponse response = stub.getUser(GetUserRequest.newBuilder().setUserId(USER_ID).build());

        assertThat(response.getId()).isEqualTo(USER_ID);
        assertThat(response.getUsername()).isEqualTo("quan");
        assertThat(response.getPhoneNumber()).isEqualTo("+84912345678");
        assertThat(response.getRole()).isEqualTo("CUSTOMER");
        assertThat(response.getActive()).isTrue();
    }

    @Test
    void userWithoutPhoneNumberLeavesTheFieldUnset() {
        User user = new User(USER_ID, "quan", "$2a$10$hash", "quan@test.com", null, "CUSTOMER");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        UserResponse response = stub.getUser(GetUserRequest.newBuilder().setUserId(USER_ID).build());

        assertThat(response.hasPhoneNumber()).isFalse();
    }

    @Test
    void unknownUserGivesNotFoundWithErrorCode() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        StatusRuntimeException e = call(USER_ID);

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(e.getTrailers().get(GrpcMetadataKeys.ERROR_CODE)).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void idThatIsNotAUserIdGivesInvalidArgumentWithoutQueryingTheDatabase() {
        for (long userId : new long[] {0, 123, 17947973414001L}) {
            StatusRuntimeException e = call(userId);

            assertThat(e.getStatus().getCode()).as("userId=%s", userId).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(e.getTrailers().get(GrpcMetadataKeys.ERROR_CODE)).isEqualTo("INVALID_USER_ID");
        }
        verifyNoInteractions(userRepository);
    }

    @Test
    void unexpectedErrorGivesInternalWithoutDetails() {
        when(userRepository.findById(USER_ID)).thenThrow(new IllegalStateException("database password is wrong"));

        StatusRuntimeException e = call(USER_ID);

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(e.getStatus().getDescription()).isEqualTo("Internal error");
    }

    private StatusRuntimeException call(long userId) {
        return catchThrowableOfType(StatusRuntimeException.class,
                () -> stub.getUser(GetUserRequest.newBuilder().setUserId(userId).build()));
    }
}
