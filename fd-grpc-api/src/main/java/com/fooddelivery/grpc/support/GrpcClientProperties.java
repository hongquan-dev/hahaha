package com.fooddelivery.grpc.support;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code fooddelivery.grpc.client.*} of a service with a gRPC client, bound and checked at startup by
 * {@link GrpcSupportAutoConfiguration}: an invalid value stops the service.
 *
 * @param defaultDeadline deadline {@link DefaultDeadlineClientInterceptor} gives a call that has none, e.g. {@code 2s}
 *                        (the default); must be positive
 */
@ConfigurationProperties("fooddelivery.grpc.client")
public record GrpcClientProperties(@DefaultValue("2s") Duration defaultDeadline) {

    public GrpcClientProperties {
        if (defaultDeadline == null || defaultDeadline.isNegative() || defaultDeadline.isZero()) {
            throw new IllegalArgumentException(
                    "fooddelivery.grpc.client.default-deadline must be positive, got: " + defaultDeadline);
        }
    }
}
