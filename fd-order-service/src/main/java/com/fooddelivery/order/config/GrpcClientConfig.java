package com.fooddelivery.order.config;

import com.fooddelivery.grpc.auth.v1.UserServiceGrpc;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ImportGrpcClients;

/**
 * gRPC stubs of the services order-service calls, created at startup as beans by Spring gRPC.
 *
 * <p>{@code UserServiceBlockingStub} uses the channel named {@code auth}, whose address is
 * {@code spring.grpc.client.channel.auth.target}. The channel connects lazily, at the first call, so order-service
 * starts even when auth-service is down.
 */
@Configuration
@ImportGrpcClients(target = "auth", types = UserServiceGrpc.UserServiceBlockingStub.class)
public class GrpcClientConfig {
}
