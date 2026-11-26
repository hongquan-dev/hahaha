package com.fooddelivery.grpc.support;

import com.fooddelivery.dto.requestid.RequestIds;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.grpc.test.v1.EchoRequest;
import com.fooddelivery.grpc.test.v1.EchoResponse;
import com.fooddelivery.grpc.test.v1.EchoServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of {@link RequestIdServerInterceptor} on an in-process gRPC server of the test-only {@code EchoService} that
 * records the request ID of the {@code ThreadContext} while it handles a call, and an interceptor inside that records
 * the {@code x-request-id} header it receives. One test runs the service on a thread pool, as a real server does.
 */
class RequestIdServerInterceptorTest {

    private static final String REQUEST_ID = "3f2a9c1e-7b4d-4e0a-9f1c-2d6e8b5a7c30";

    private final AtomicReference<String> idWhileHandling = new AtomicReference<>();
    private final AtomicReference<String> headerInside = new AtomicReference<>();
    private Server server;
    private ManagedChannel channel;
    private ExecutorService pool;

    // Not every test starts a server
    @AfterEach
    void stop() {
        if (channel != null) {
            channel.shutdownNow();
            server.shutdownNow();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
        ThreadContext.clearMap();
    }

    @Test
    void idOfTheCallerIsUsedWhileHandling() throws Exception {
        call(stub(null), REQUEST_ID);

        assertThat(idWhileHandling).hasValue(REQUEST_ID);
        assertThat(headerInside).hasValue(REQUEST_ID);
    }

    @Test
    void callWithoutIdGetsANewOneSeenInsideToo() throws Exception {
        call(stub(null), null);

        assertThat(RequestIds.isValid(idWhileHandling.get())).isTrue();
        assertThat(headerInside.get()).isEqualTo(idWhileHandling.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "12345678901234567890123456789012345678901234567890123456789012345"})
    void invalidIdIsReplacedByANewOne(String invalid) throws Exception {
        call(stub(null), invalid);

        assertThat(idWhileHandling.get()).isNotEqualTo(invalid);
        assertThat(RequestIds.isValid(idWhileHandling.get())).isTrue();
        assertThat(headerInside.get()).isEqualTo(idWhileHandling.get());
    }

    @Test
    void previousValueOfTheThreadIsRestoredAfterTheCall() throws Exception {
        EchoServiceGrpc.EchoServiceBlockingStub stub = stub(null);
        ThreadContext.put(RequestIds.THREAD_CONTEXT_KEY, "outer");

        call(stub, REQUEST_ID);

        assertThat(idWhileHandling).hasValue(REQUEST_ID);
        assertThat(ThreadContext.get(RequestIds.THREAD_CONTEXT_KEY)).isEqualTo("outer");
    }

    @Test
    void idIsSetOnThePoolThreadAndNotLeftThere() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        EchoServiceGrpc.EchoServiceBlockingStub stub = stub(pool);

        call(stub, REQUEST_ID);

        assertThat(idWhileHandling).hasValue(REQUEST_ID);
        assertThat(pool.submit(() -> ThreadContext.get(RequestIds.THREAD_CONTEXT_KEY)).get()).isNull();
    }

    @Test
    void metadataKeyIsTheHttpHeaderOfTheGatewayInLowercase() {
        assertThat(GrpcMetadataKeys.REQUEST_ID.name()).isEqualTo(RequestIds.HEADER.toLowerCase(Locale.ROOT));
    }

    // Stub to a server whose service runs on the given executor, or on the calling thread if null
    private EchoServiceGrpc.EchoServiceBlockingStub stub(ExecutorService executor) throws Exception {
        EchoServiceGrpc.EchoServiceImplBase service = new EchoServiceGrpc.EchoServiceImplBase() {
            @Override
            public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
                idWhileHandling.set(ThreadContext.get(RequestIds.THREAD_CONTEXT_KEY));
                responseObserver.onNext(EchoResponse.newBuilder().setText(request.getText()).build());
                responseObserver.onCompleted();
            }
        };
        ServerInterceptor inside = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                         Metadata headers,
                                                                         ServerCallHandler<ReqT, RespT> next) {
                headerInside.set(headers.get(GrpcMetadataKeys.REQUEST_ID));
                return next.startCall(call, headers);
            }
        };
        String name = InProcessServerBuilder.generateName();
        InProcessServerBuilder builder = InProcessServerBuilder.forName(name);
        if (executor == null) {
            builder.directExecutor();
        } else {
            builder.executor(executor);
        }
        // The last interceptor runs first, so the request ID interceptor is outside the recorder
        server = builder.addService(ServerInterceptors.intercept(service, inside, new RequestIdServerInterceptor()))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return EchoServiceGrpc.newBlockingStub(channel);
    }

    // Calls with this x-request-id header, none if null
    private static void call(EchoServiceGrpc.EchoServiceBlockingStub stub, String requestId) {
        Metadata headers = new Metadata();
        if (requestId != null) {
            headers.put(GrpcMetadataKeys.REQUEST_ID, requestId);
        }
        stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .echo(EchoRequest.newBuilder().setText("hello").build());
    }
}
