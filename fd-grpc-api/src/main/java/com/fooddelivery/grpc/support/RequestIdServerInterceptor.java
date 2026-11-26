package com.fooddelivery.grpc.support;

import com.fooddelivery.dto.requestid.RequestIds;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;

import java.util.function.Supplier;

/**
 * Puts the request ID of every gRPC call into the Log4j2 {@code ThreadContext} while the call is handled, so every log
 * line of the service for that call carries it. The ID comes from the {@link GrpcMetadataKeys#REQUEST_ID} header sent
 * by {@link RequestIdClientInterceptor}; a call without a valid one (e.g. from grpcurl) gets a new ID.
 *
 * <p>Registered by {@link GrpcSupportAutoConfiguration} and applied to every gRPC service of the application
 * ({@code @GlobalServerInterceptor}), outside {@link GrpcLoggingInterceptor}, which reads the ID back from the headers.
 * gRPC may run each callback of a call on a different thread, so the ID is set around every callback and the
 * previous value restored after it, never left on a pooled thread.
 */
@GlobalServerInterceptor
@Order(GrpcExceptionHandlerInterceptor.ORDER - 2)
public class RequestIdServerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        String received = headers.get(GrpcMetadataKeys.REQUEST_ID);
        String requestId = RequestIds.orGenerate(received);
        // Headers carry the ID used, so the interceptors inside (e.g. logging) see the same one
        headers.discardAll(GrpcMetadataKeys.REQUEST_ID);
        headers.put(GrpcMetadataKeys.REQUEST_ID, requestId);

        ServerCall.Listener<ReqT> listener = callWithRequestId(requestId, () -> next.startCall(call, headers));
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            @Override
            public void onMessage(ReqT message) {
                withRequestId(requestId, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                withRequestId(requestId, super::onHalfClose);
            }

            @Override
            public void onCancel() {
                withRequestId(requestId, super::onCancel);
            }

            @Override
            public void onComplete() {
                withRequestId(requestId, super::onComplete);
            }

            @Override
            public void onReady() {
                withRequestId(requestId, super::onReady);
            }
        };
    }

    private static void withRequestId(String requestId, Runnable action) {
        callWithRequestId(requestId, () -> {
            action.run();
            return null;
        });
    }

    // Runs the action with the ID in the ThreadContext, then puts back what the thread had before, null included
    private static <T> T callWithRequestId(String requestId, Supplier<T> action) {
        String previous = ThreadContext.get(RequestIds.THREAD_CONTEXT_KEY);
        ThreadContext.put(RequestIds.THREAD_CONTEXT_KEY, requestId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                ThreadContext.remove(RequestIds.THREAD_CONTEXT_KEY);
            } else {
                ThreadContext.put(RequestIds.THREAD_CONTEXT_KEY, previous);
            }
        }
    }
}
