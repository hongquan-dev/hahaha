package com.fooddelivery.grpc.support;

import com.fooddelivery.grpc.test.v1.EchoRequest;
import com.fooddelivery.grpc.test.v1.EchoResponse;
import com.fooddelivery.grpc.test.v1.EchoServiceGrpc;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Tests of {@link DefaultDeadlineClientInterceptor} on an in-process gRPC server of the test-only {@code EchoService}
 * that never answers, so a call only ends by its deadline. A recording interceptor next to the channel shows the
 * deadline each call leaves with.
 */
class DefaultDeadlineClientInterceptorTest {

    private final AtomicReference<Deadline> sentDeadline = new AtomicReference<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void start() throws Exception {
        String name = InProcessServerBuilder.generateName();
        EchoServiceGrpc.EchoServiceImplBase silent = new EchoServiceGrpc.EchoServiceImplBase() {
            @Override
            public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor().addService(silent).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
        scheduler.shutdownNow();
    }

    @Test
    void callWithoutDeadlineGetsTheDefault() {
        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> stubWithDefault(Duration.ofMillis(200)).echo(EchoRequest.getDefaultInstance()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        assertThat(sentDeadline.get()).isNotNull();
        assertThat(sentDeadline.get().timeRemaining(TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(200);
    }

    @Test
    void deadlineOfTheClientIsKept() {
        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> stubWithDefault(Duration.ofMinutes(5)).withDeadlineAfter(200, TimeUnit.MILLISECONDS)
                        .echo(EchoRequest.getDefaultInstance()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        assertThat(sentDeadline.get().timeRemaining(TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(200);
    }

    @Test
    void earlierDeadlineOfTheCallBeingServedWins() {
        EchoServiceGrpc.EchoServiceBlockingStub stub = stubWithDefault(Duration.ofMinutes(5));
        Context.CancellableContext served = Context.current()
                .withDeadline(Deadline.after(200, TimeUnit.MILLISECONDS), scheduler);
        long start = System.nanoTime();

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> served.run(() -> stub.echo(EchoRequest.getDefaultInstance())));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        served.cancel(null);
    }

    // Stub whose calls go through the interceptor under test, then the recorder, then the channel
    private EchoServiceGrpc.EchoServiceBlockingStub stubWithDefault(Duration defaultDeadline) {
        ClientInterceptor recorder = new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                       CallOptions callOptions, Channel next) {
                sentDeadline.set(callOptions.getDeadline());
                return next.newCall(method, callOptions);
            }
        };
        Channel intercepted = ClientInterceptors.intercept(channel, recorder,
                new DefaultDeadlineClientInterceptor(defaultDeadline));
        return EchoServiceGrpc.newBlockingStub(intercepted);
    }
}
