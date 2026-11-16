package com.fooddelivery.grpc.support;

import com.fooddelivery.grpc.GrpcMetadataKeys;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of {@link GrpcSupportAutoConfiguration} with {@link ApplicationContextRunner}: which beans a service gets
 * depending on whether the gRPC server and client auto-configurations are on its classpath, and back-off in favour of
 * the service's own beans. No gRPC server is started.
 */
class GrpcSupportAutoConfigurationTest {
    private static final String SERVER_AUTOCONFIGURATION =
            "org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration";
    private static final String CLIENT_AUTOCONFIGURATION =
            "org.springframework.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GrpcSupportAutoConfiguration.class))
            .withPropertyValues("spring.application.name=service-test");

    @Test
    void isListedInAutoConfigurationImports() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()))
                .contains(GrpcSupportAutoConfiguration.class.getName());
    }

    @Test
    void serviceWithServerAndClientGetsAllBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(BusinessExceptionGrpcHandler.class);
            assertThat(context).hasSingleBean(GrpcLoggingInterceptor.class);
            assertThat(context).hasSingleBean(CallerServiceClientInterceptor.class);
        });
    }

    @Test
    void serviceWithOnlyAServerGetsNoClientBean() {
        runner.withClassLoader(new FilteredClassLoader(CLIENT_AUTOCONFIGURATION)).run(context -> {
            assertThat(context).hasSingleBean(BusinessExceptionGrpcHandler.class);
            assertThat(context).hasSingleBean(GrpcLoggingInterceptor.class);
            assertThat(context).doesNotHaveBean(CallerServiceClientInterceptor.class);
        });
    }

    @Test
    void serviceWithOnlyAClientGetsNoServerBean() {
        runner.withClassLoader(new FilteredClassLoader(SERVER_AUTOCONFIGURATION)).run(context -> {
            assertThat(context).doesNotHaveBean(BusinessExceptionGrpcHandler.class);
            assertThat(context).doesNotHaveBean(GrpcLoggingInterceptor.class);
            assertThat(context).hasSingleBean(CallerServiceClientInterceptor.class);
        });
    }

    @Test
    void clientInterceptorSendsTheApplicationName() {
        runner.run(context -> {
            CallerServiceClientInterceptor interceptor = context.getBean(CallerServiceClientInterceptor.class);
            Metadata headers = new Metadata();

            ClientCall<Object, Object> call = interceptor.interceptCall(null, CallOptions.DEFAULT, new NoopChannel());
            call.start(new ClientCall.Listener<>() {}, headers);

            assertThat(headers.get(GrpcMetadataKeys.CALLER_SERVICE)).isEqualTo("service-test");
        });
    }

    @Test
    void backsOffWhenTheServiceDeclaresItsOwnBeans() {
        runner.withUserConfiguration(CustomBeans.class).run(context -> {
            assertThat(context).hasSingleBean(BusinessExceptionGrpcHandler.class);
            assertThat(context.getBean(BusinessExceptionGrpcHandler.class))
                    .isSameAs(context.getBean("customHandler"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBeans {

        @Bean
        BusinessExceptionGrpcHandler customHandler() {
            return new BusinessExceptionGrpcHandler();
        }
    }

    // Channel whose calls do nothing, so the interceptor's start can run without a server
    private static class NoopChannel extends Channel {

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method,
                                                             CallOptions callOptions) {
            return new ClientCall<>() {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                }

                @Override
                public void request(int numMessages) {
                }

                @Override
                public void cancel(String message, Throwable cause) {
                }

                @Override
                public void halfClose() {
                }

                @Override
                public void sendMessage(ReqT message) {
                }
            };
        }

        @Override
        public String authority() {
            return "noop";
        }
    }
}
