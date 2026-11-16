package com.fooddelivery.auth.grpc;

import com.fooddelivery.auth.dto.InternalUserDto;
import com.fooddelivery.auth.service.user.UserQueryService;
import com.fooddelivery.auth.validation.InternalUserValidator;
import com.fooddelivery.grpc.auth.v1.GetUserRequest;
import com.fooddelivery.grpc.auth.v1.UserResponse;
import com.fooddelivery.grpc.auth.v1.UserServiceGrpc;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.grpc.server.service.GrpcService;

/**
 * gRPC {@code fooddelivery.auth.v1.UserService} (contract in {@code auth/v1/user_service.proto} of fd-grpc-api), served
 * on {@code spring.grpc.server.port} for other services inside the network. The gRPC counterpart of
 * {@code InternalUserController}: same check, same service, same fields.
 *
 * <p>Plays the role of the controller for gRPC: there is no API flow, so it checks the input itself with
 * {@link InternalUserValidator#checkUserId}, then maps between proto messages and {@link UserQueryService}. A
 * {@code BusinessException} thrown here is turned into a gRPC status by {@code BusinessExceptionGrpcHandler} of
 * fd-grpc-api, registered automatically.
 */
@GrpcService
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {
    private static final Logger log = LogManager.getLogger(UserGrpcService.class);

    private final UserQueryService userQueryService;

    public UserGrpcService(UserQueryService userQueryService) {
        this.userQueryService = userQueryService;
    }

    /**
     * {@code UserService/GetUser}: the user with this ID, active or locked.
     *
     * <p>Flow: {@link InternalUserValidator#checkUserId} ({@code INVALID_ARGUMENT}) -> {@link UserQueryService#getById}
     * ({@code NOT_FOUND}) -> {@link UserResponse}.
     */
    @Override
    public void getUser(GetUserRequest request, StreamObserver<UserResponse> responseObserver) {
        log.info("[VALIDATE] [gRPC] UserService/GetUser | userId={}", request.getUserId());
        InternalUserValidator.checkUserId(request.getUserId());

        InternalUserDto user = userQueryService.getById(request.getUserId());
        responseObserver.onNext(toProto(user));
        responseObserver.onCompleted();
    }

    // Protobuf has no null: a user without phone number leaves the optional field unset (has_phone_number() false)
    private static UserResponse toProto(InternalUserDto user) {
        UserResponse.Builder builder = UserResponse.newBuilder()
                .setId(user.id())
                .setUsername(user.username())
                .setRole(user.role())
                .setActive(user.active());
        if (user.phoneNumber() != null) {
            builder.setPhoneNumber(user.phoneNumber());
        }
        return builder.build();
    }
}
