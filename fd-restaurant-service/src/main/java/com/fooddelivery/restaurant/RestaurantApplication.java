package com.fooddelivery.restaurant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the restaurant service, which owns restaurants. Listens on port 8082 ({@code server.port} in
 * {@code application.properties}).
 *
 * <p>Every API of the service goes through the API flow of {@code common-api-flow-lib}: it must be declared in
 * {@code api-routes.xml}, the caller's role is checked against the route's {@code <roles>}, its body against the
 * route's JSON Schema, then the route's validator runs before the controller.
 */
@SpringBootApplication
public class RestaurantApplication {
    public static void main(String[] args) {
        SpringApplication.run(RestaurantApplication.class, args);
    }
}
