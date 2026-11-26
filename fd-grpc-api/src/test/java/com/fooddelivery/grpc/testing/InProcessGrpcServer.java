package com.fooddelivery.grpc.testing;

import com.fooddelivery.grpc.support.BusinessExceptionGrpcHandler;
import com.fooddelivery.grpc.support.GrpcLoggingInterceptor;
import com.fooddelivery.grpc.support.RequestIdServerInterceptor;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;

import java.io.IOException;

/**
 * In-process gRPC server (no port) for the tests of the gRPC services and clients, with the server interceptors of a
 * running service in their real order: {@link RequestIdServerInterceptor}, then {@link GrpcLoggingInterceptor}, then
 * the exception interceptor of Spring gRPC wired to {@link BusinessExceptionGrpcHandler}. A test of an
 * {@code XxxGrpcService} therefore sees exactly the status and {@code error-code} trailer a real client receives.
 *
 * <p>Shipped in the test jar of fd-grpc-api ({@code <type>test-jar</type>}, scope {@code test}); the service also
 * needs {@code grpc-inprocess} in scope {@code test}. Calls run on the calling thread, so tests need no waiting.
 *
 * <pre>
 * try (InProcessGrpcServer server = InProcessGrpcServer.start(new UserGrpcService(userQueryService))) {
 *     UserServiceGrpc.UserServiceBlockingStub stub = UserServiceGrpc.newBlockingStub(server.channel());
 *     ...
 * }
 * </pre>
 */
public final class InProcessGrpcServer implements AutoCloseable {

    private final Server server;
    private final ManagedChannel channel;

    private InProcessGrpcServer(Server server, ManagedChannel channel) {
        this.server = server;
        this.channel = channel;
    }

    /**
     * Starts a server with these services behind the interceptors of a running service.
     *
     * <p>Throws {@code IllegalStateException} if the server cannot start, which only happens on a broken test setup.
     *
     * @param services the gRPC services under test, or fakes of the called service in a client test
     * @return the started server; close it after the test
     */
    public static InProcessGrpcServer start(BindableService... services) {
        String name = InProcessServerBuilder.generateName();
        InProcessServerBuilder builder = InProcessServerBuilder.forName(name).directExecutor();
        for (BindableService service : services) {
            // The last interceptor runs first: request ID, then logging, then the exception handler, as in a service
            builder.addService(ServerInterceptors.intercept(service,
                    new GrpcExceptionHandlerInterceptor(new BusinessExceptionGrpcHandler()),
                    new GrpcLoggingInterceptor(),
                    new RequestIdServerInterceptor()));
        }
        try {
            Server server = builder.build().start();
            return new InProcessGrpcServer(server, InProcessChannelBuilder.forName(name).directExecutor().build());
        } catch (IOException e) {
            throw new IllegalStateException("In-process gRPC server did not start", e);
        }
    }

    // Channel to this server, for the stub under test, e.g. UserServiceGrpc.newBlockingStub(server.channel())
    public ManagedChannel channel() {
        return channel;
    }

    // Stops the server only, so calls get UNAVAILABLE as when the called service is down
    public void stopServer() {
        server.shutdownNow();
    }

    @Override
    public void close() {
        channel.shutdownNow();
        server.shutdownNow();
    }
}
