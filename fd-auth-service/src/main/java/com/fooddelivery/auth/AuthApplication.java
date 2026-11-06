package com.fooddelivery.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the auth service, which owns user accounts. Listens on port 8081 ({@code server.port} in
 * {@code application.properties}).
 *
 * <p>Every API of the service goes through the API flow of {@code common-api-flow-lib}: it must be declared in
 * {@code api-routes.xml}, its body is checked against the route's JSON Schema, then the route's validator runs before
 * the controller.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
