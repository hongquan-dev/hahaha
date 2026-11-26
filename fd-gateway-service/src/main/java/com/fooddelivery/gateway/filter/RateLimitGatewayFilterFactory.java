package com.fooddelivery.gateway.filter;

import com.fooddelivery.dto.requestid.RequestIds;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.gateway.ratelimit.FixedWindowRateLimiter;
import com.fooddelivery.gateway.ratelimit.RateLimitDecision;
import com.fooddelivery.gateway.ratelimit.RateLimitProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;

/**
 * Gateway filter {@code RateLimit}: lets one client IP send at most {@code gateway.rate-limit.limit} requests to one
 * path per {@code gateway.rate-limit.window}, against password guessing and mass registration. Added to a route in
 * {@code application.properties}, e.g.
 * <pre>
 * spring.cloud.gateway.server.webflux.routes[0].filters[0]=RateLimit
 * </pre>
 *
 * <p>At request time, counts the request under its method, path and client IP with {@link FixedWindowRateLimiter}.
 * Within the limit, the request goes on unchanged; past it, the gateway answers 429 {@code TOO_MANY_REQUESTS} itself,
 * with {@code Retry-After} in seconds and an {@code ApiResponse} body, and the service never sees the request.
 *
 * <p>The client IP is the address of the direct connection, or with {@code gateway.rate-limit.trusted-proxy-hops} above
 * 0 the one the trusted proxies wrote in {@code X-Forwarded-For}. Behind a proxy without that setting, every client
 * would share the proxy's limit.
 */
@Component
public class RateLimitGatewayFilterFactory extends AbstractGatewayFilterFactory<RateLimitGatewayFilterFactory.Config> {
    private static final Logger log = LogManager.getLogger(RateLimitGatewayFilterFactory.class);

    // Used when the connection has no address, e.g. in some test setups; all such requests share one limit
    static final String UNKNOWN_CLIENT = "unknown";

    private final FixedWindowRateLimiter limiter;
    private final JsonMapper jsonMapper;
    private final RemoteAddressResolver clientAddressResolver;

    public RateLimitGatewayFilterFactory(FixedWindowRateLimiter limiter, JsonMapper jsonMapper,
                                         RateLimitProperties properties) {
        super(Config.class);
        this.limiter = limiter;
        this.jsonMapper = jsonMapper;
        this.clientAddressResolver = properties.trustedProxyHops() == 0
                ? new RemoteAddressResolver() { }
                : XForwardedRemoteAddressResolver.maxTrustedIndex(properties.trustedProxyHops());
    }

    @Override
    public GatewayFilter apply(Config config) {
        return this::filter;
    }

    private Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String client = clientIp(exchange);
        RateLimitDecision decision = limiter.tryAcquire(request.getMethod() + " " + request.getPath().value() + " "
                + client);
        if (decision.allowed()) {
            return chain.filter(exchange);
        }

        log.warn("[RATE_LIMIT] {} {} | requestId={}, client={} over the limit, retry in {}s", request.getMethod(),
                request.getPath().value(), request.getHeaders().getFirst(RequestIds.HEADER), client,
                retryAfterSeconds(decision));
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER,
                String.valueOf(retryAfterSeconds(decision)));
        return ErrorResponses.write(exchange, jsonMapper, ApiStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                "Too many requests, please try again later");
    }

    private String clientIp(ServerWebExchange exchange) {
        InetSocketAddress address = clientAddressResolver.resolve(exchange);
        if (address == null) {
            return UNKNOWN_CLIENT;
        }
        return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
    }

    // Whole seconds, rounded up so the client never retries a moment too early, and at least 1
    private static long retryAfterSeconds(RateLimitDecision decision) {
        long millis = decision.retryAfter().toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

    /**
     * Arguments of the filter in a route definition. None for now: the limit is set once for all routes in
     * {@code gateway.rate-limit.*}.
     */
    public static class Config {
    }
}
