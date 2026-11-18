package com.fooddelivery.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the API gateway. Listens on port 8090 ({@code server.port} in {@code application.properties}).
 *
 * <p>{@code @ConfigurationPropertiesScan} binds the {@code @ConfigurationProperties} records, e.g.
 * {@code GatewayAuthProperties}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}