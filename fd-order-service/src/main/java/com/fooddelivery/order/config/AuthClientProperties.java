package com.fooddelivery.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Settings of the calls to auth-service, bound at startup from the {@code services.auth.*} properties of
 * {@code application.properties}. The gRPC address is not here: it is the {@code auth} channel of
 * {@code spring.grpc.client.channel.*}.
 *
 * <p>Checked when bound: a missing URL or a non-positive timeout throws, so the application refuses to start.
 *
 * @param httpUrl base URL of the HTTP API, e.g. {@code http://localhost:8081}
 * @param timeout maximum wait for one call, HTTP (connect and read) or gRPC (deadline); default 2s
 */
@ConfigurationProperties(prefix = "services.auth")
public record AuthClientProperties(URI httpUrl, @DefaultValue("2s") Duration timeout) {

    public AuthClientProperties {
        if (httpUrl == null) {
            throw new IllegalArgumentException("services.auth.http-url must be set");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("services.auth.timeout must be positive, got " + timeout);
        }
    }
}
