package com.fooddelivery.grpc.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of {@link GrpcServerDefaultsEnvironmentPostProcessor} on a real, empty {@link SpringApplication} (no web
 * server, no beans), so registration in {@code spring.factories} and the order against {@code application.properties}
 * are those of a running service. The {@code application.properties} of each test is a temporary file.
 */
class GrpcServerDefaultsEnvironmentPostProcessorTest {

    @TempDir
    Path dir;

    @Test
    void serviceWithoutTheSettingsGetsTheSafeDefaults() throws IOException {
        Environment environment = startWith("server.port=8082\n");

        assertThat(environment.getProperty("spring.grpc.server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("spring.grpc.server.reflection.enabled")).isEqualTo("false");
    }

    @Test
    void applicationPropertiesWin() throws IOException {
        Environment environment = startWith("""
                spring.grpc.server.address=0.0.0.0
                spring.grpc.server.reflection.enabled=true
                """);

        assertThat(environment.getProperty("spring.grpc.server.address")).isEqualTo("0.0.0.0");
        assertThat(environment.getProperty("spring.grpc.server.reflection.enabled")).isEqualTo("true");
    }

    @Test
    void settingOfTheDeploymentWins() throws IOException {
        Environment environment = startWith("", "--spring.grpc.server.address=0.0.0.0");

        assertThat(environment.getProperty("spring.grpc.server.address")).isEqualTo("0.0.0.0");
        assertThat(environment.getProperty("spring.grpc.server.reflection.enabled")).isEqualTo("false");
    }

    // Environment of an application started with this application.properties and these command-line arguments
    private Environment startWith(String properties, String... args) throws IOException {
        Path file = dir.resolve("application.properties");
        Files.writeString(file, properties);
        SpringApplication application = new SpringApplication(Empty.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        String[] allArgs = new String[args.length + 1];
        allArgs[0] = "--spring.config.location=file:" + file;
        System.arraycopy(args, 0, allArgs, 1, args.length);
        try (ConfigurableApplicationContext context = application.run(allArgs)) {
            return context.getEnvironment();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }
}
