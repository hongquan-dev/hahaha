package com.fooddelivery.auth.grpc;

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
import org.springframework.stereotype.Component;

/**
 * Logs one line per gRPC call when it ends: method, status and duration, e.g.
 * {@code [gRPC] fooddelivery.auth.v1.UserService/GetUser | status=NOT_FOUND (User 17000000001000 not found), 3ms}.
 *
 * <p>Applied to every gRPC service of this application ({@code @GlobalServerInterceptor}). Ordered just before
 * {@link GrpcExceptionHandlerInterceptor}, i.e. outside it: that interceptor closes a failed call with the status of
 * {@code BusinessExceptionGrpcHandler}, and an interceptor inside it would never see that close, so errors would not be
 * logged. Never logs the messages themselves or the metadata, which may carry tokens.
 */
@Component
@GlobalServerInterceptor
@Order(GrpcExceptionHandlerInterceptor.ORDER - 1)
public class GrpcLoggingInterceptor implements ServerInterceptor {
    private static final Logger log = LogManager.getLogger(GrpcLoggingInterceptor.class);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        long start = System.nanoTime();
        String method = call.getMethodDescriptor().getFullMethodName();
        ServerCall<ReqT, RespT> logged = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                long millis = (System.nanoTime() - start) / 1_000_000;
                log.info("[gRPC] {} | status={}{}, {}ms", method, status.getCode(),
                        status.getDescription() == null ? "" : " (" + status.getDescription() + ")", millis);
                super.close(status, trailers);
            }
        };
        return next.startCall(logged, headers);
    }
}
