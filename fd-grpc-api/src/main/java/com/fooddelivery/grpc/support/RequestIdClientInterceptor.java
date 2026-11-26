package com.fooddelivery.grpc.support;

import com.fooddelivery.dto.requestid.RequestIds;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.grpc.client.GlobalClientInterceptor;

/**
 * Sends the request ID of the Log4j2 {@code ThreadContext} with every gRPC call of the application, in the
 * {@link GrpcMetadataKeys#REQUEST_ID} header, so the called service logs under the same ID
 * ({@link RequestIdServerInterceptor}). Registered by {@link GrpcSupportAutoConfiguration} in every service that uses a
 * gRPC client and applied to every channel ({@code @GlobalClientInterceptor}).
 *
 * <p>The ID is read on the thread that starts the call, i.e. the thread handling the request. Without a valid one
 * (e.g. a scheduled job) no header is sent and the called service makes a new ID.
 */
@GlobalClientInterceptor
public class RequestIdClientInterceptor implements ClientInterceptor {

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String requestId = ThreadContext.get(RequestIds.THREAD_CONTEXT_KEY);
                if (RequestIds.isValid(requestId)) {
                    headers.put(GrpcMetadataKeys.REQUEST_ID, requestId);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
