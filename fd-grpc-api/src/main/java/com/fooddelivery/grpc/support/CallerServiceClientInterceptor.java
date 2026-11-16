package com.fooddelivery.grpc.support;

import com.fooddelivery.grpc.GrpcMetadataKeys;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.springframework.grpc.client.GlobalClientInterceptor;

/**
 * Adds the {@code x-caller-service} header ({@link GrpcMetadataKeys#CALLER_SERVICE}) with
 * {@code spring.application.name}, e.g. {@code service-order}, to every gRPC call of the application, so the server
 * can log who called it. Registered by {@link GrpcSupportAutoConfiguration} in every service that uses a gRPC client
 * and applied to every channel ({@code @GlobalClientInterceptor}).
 */
@GlobalClientInterceptor
public class CallerServiceClientInterceptor implements ClientInterceptor {

    private final String applicationName;

    public CallerServiceClientInterceptor(String applicationName) {
        this.applicationName = applicationName;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(GrpcMetadataKeys.CALLER_SERVICE, applicationName);
                super.start(responseListener, headers);
            }
        };
    }
}
