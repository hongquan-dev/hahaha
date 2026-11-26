package com.fooddelivery.grpc.support;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import org.springframework.grpc.client.GlobalClientInterceptor;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Gives every gRPC call of the application that has no deadline the default one,
 * {@code fooddelivery.grpc.client.default-deadline}, so a client that forgets {@code withDeadlineAfter} cannot wait
 * forever. Registered by {@link GrpcSupportAutoConfiguration} in every service that uses a gRPC client and applied to
 * every channel ({@code @GlobalClientInterceptor}).
 *
 * <p>A deadline set by the client is kept. Inside a gRPC call being served, grpc-java also applies the deadline of
 * that call ({@code Context}), and the earlier of the two wins, so a chain of calls never outlives the first one.
 */
@GlobalClientInterceptor
public class DefaultDeadlineClientInterceptor implements ClientInterceptor {

    private final Duration defaultDeadline;

    public DefaultDeadlineClientInterceptor(Duration defaultDeadline) {
        this.defaultDeadline = defaultDeadline;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions, Channel next) {
        if (callOptions.getDeadline() == null) {
            callOptions = callOptions.withDeadlineAfter(defaultDeadline.toNanos(), TimeUnit.NANOSECONDS);
        }
        return next.newCall(method, callOptions);
    }
}
