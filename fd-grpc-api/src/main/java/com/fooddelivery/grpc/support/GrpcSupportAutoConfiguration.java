package com.fooddelivery.grpc.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gRPC support every service gets by depending on fd-grpc-api, with no code of its own. Registered in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <ul>
 *   <li>Service with a gRPC server ({@code spring-boot-starter-grpc-server}): {@link BusinessExceptionGrpcHandler} and
 *       {@link GrpcLoggingInterceptor}.</li>
 *   <li>Service with a gRPC client ({@code spring-boot-starter-grpc-client}): {@link CallerServiceClientInterceptor}.</li>
 * </ul>
 *
 * <p>Each side is detected by the class of its Spring Boot auto-configuration, so a service that only calls others
 * gets no server beans, and the other way round. A service replaces a bean by declaring its own of the same type.
 */
@AutoConfiguration
public class GrpcSupportAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration",
            "com.fooddelivery.web.exception.BusinessException"})
    static class Server {

        @Bean
        @ConditionalOnMissingBean
        BusinessExceptionGrpcHandler businessExceptionGrpcHandler() {
            return new BusinessExceptionGrpcHandler();
        }

        @Bean
        @ConditionalOnMissingBean
        GrpcLoggingInterceptor grpcLoggingInterceptor() {
            return new GrpcLoggingInterceptor();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration")
    static class Client {

        @Bean
        @ConditionalOnMissingBean
        CallerServiceClientInterceptor callerServiceClientInterceptor(
                @Value("${spring.application.name}") String applicationName) {
            return new CallerServiceClientInterceptor(applicationName);
        }
    }
}
