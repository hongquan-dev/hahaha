package com.fooddelivery.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the order service. Listens on port 8083 ({@code server.port} in {@code application.properties}).
 *
 * <p>{@code @ConfigurationPropertiesScan} binds the {@code @ConfigurationProperties} records, e.g.
 * {@code AuthClientProperties}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
