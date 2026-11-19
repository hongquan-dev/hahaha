package com.fooddelivery.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings of the calls to auth-service, bound at startup from the {@code services.auth.*} properties of
 * {@code application.properties}. The address is not here: it is the {@code auth} channel of
 * {@code spring.grpc.client.channel.*}.
 *
 * <p>Checked when bound: a non-positive timeout throws, so the application refuses to start.
 *
 * @param timeout deadline of one gRPC call; default 2s
 */
@ConfigurationProperties(prefix = "services.auth")
public record AuthClientProperties(@DefaultValue("2s") Duration timeout) {

    public AuthClientProperties {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("services.auth.timeout must be positive, got " + timeout);
        }
    }
}
