package com.fooddelivery.grpc.support;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Safe defaults for the gRPC server of every service that depends on fd-grpc-api, so a new gRPC server is safe even
 * if its {@code application.properties} forgets them:
 * <ul>
 *   <li>{@code spring.grpc.server.address=127.0.0.1}: only this machine reaches it, as the HTTP ports of the
 *       services.</li>
 *   <li>{@code spring.grpc.server.reflection.enabled=false}: only services with the {@code .proto} contracts call
 *       gRPC, nobody needs to list them.</li>
 * </ul>
 *
 * <p>Runs at startup, registered in {@code META-INF/spring.factories}, after {@link ConfigDataEnvironmentPostProcessor}
 * so its property source is added after {@code application.properties}: every other source wins, e.g. a line of
 * {@code application.properties} or {@code SPRING_GRPC_SERVER_ADDRESS=0.0.0.0} of a deployment.
 */
public class GrpcServerDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    // Name of the property source, e.g. in the /actuator/env output
    static final String SOURCE_NAME = "fdGrpcServerDefaults";

    private static final Map<String, Object> DEFAULTS = Map.of(
            "spring.grpc.server.address", "127.0.0.1",
            "spring.grpc.server.reflection.enabled", "false");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, DEFAULTS));
    }

    // After the config data post-processor, which adds application.properties at the end of the sources
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
