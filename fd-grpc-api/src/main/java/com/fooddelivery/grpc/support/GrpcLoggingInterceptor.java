package com.fooddelivery.grpc.support;

import com.fooddelivery.grpc.GrpcMetadataKeys;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;

/**
 * Logs one line per gRPC call when it ends: method, calling service, request ID, status and duration, e.g.
 * {@code [gRPC] fooddelivery.restaurant.v1.RestaurantService/GetRestaurant | caller=service-order,
 * requestId=3f2a9c1e-7b4d-4e0a-9f1c-2d6e8b5a7c30, status=NOT_FOUND (Restaurant 17000000001002 not found), 3ms}. The
 * caller comes from the {@link GrpcMetadataKeys#CALLER_SERVICE} header, {@code null} if absent (e.g. grpcurl); the
 * request ID from {@link GrpcMetadataKeys#REQUEST_ID}, always set by {@link RequestIdServerInterceptor} outside this
 * one, as the close may run on a thread without it in the {@code ThreadContext}.
 *
 * <p>Registered by {@link GrpcSupportAutoConfiguration} and applied to every gRPC service of the application
 * ({@code @GlobalServerInterceptor}). Ordered just before {@link GrpcExceptionHandlerInterceptor}, i.e. outside it: that
 * interceptor closes a failed call with the status of {@link BusinessExceptionGrpcHandler}, and an interceptor inside it
 * would never see that close, so errors would not be logged. Never logs the messages themselves or the metadata, which
 * may carry tokens.
 */
@GlobalServerInterceptor
@Order(GrpcExceptionHandlerInterceptor.ORDER - 1)
public class GrpcLoggingInterceptor implements ServerInterceptor {
    private static final Logger log = LogManager.getLogger(GrpcLoggingInterceptor.class);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        long start = System.nanoTime();
        String method = call.getMethodDescriptor().getFullMethodName();
        String caller = headers.get(GrpcMetadataKeys.CALLER_SERVICE);
        String requestId = headers.get(GrpcMetadataKeys.REQUEST_ID);
        ServerCall<ReqT, RespT> logged = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                long millis = (System.nanoTime() - start) / 1_000_000;
                log.info("[gRPC] {} | caller={}, requestId={}, status={}{}, {}ms", method, caller, requestId,
                        status.getCode(),
                        status.getDescription() == null ? "" : " (" + status.getDescription() + ")", millis);
                super.close(status, trailers);
            }
        };
        return next.startCall(logged, headers);
    }
}
