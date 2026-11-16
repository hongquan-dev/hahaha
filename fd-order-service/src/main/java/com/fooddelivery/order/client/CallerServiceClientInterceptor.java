package com.fooddelivery.order.client;

import com.fooddelivery.grpc.GrpcMetadataKeys;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.stereotype.Component;

/**
 * Adds the {@code x-caller-service} header ({@code GrpcMetadataKeys.CALLER_SERVICE}) with
 * {@code spring.application.name}, e.g. {@code service-order}, to every gRPC call of this application, so the server
 * can log who called it. Applied to every channel ({@code @GlobalClientInterceptor}).
 */
@Component
@GlobalClientInterceptor
public class CallerServiceClientInterceptor implements ClientInterceptor {

    private final String applicationName;

    public CallerServiceClientInterceptor(@Value("${spring.application.name}") String applicationName) {
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
