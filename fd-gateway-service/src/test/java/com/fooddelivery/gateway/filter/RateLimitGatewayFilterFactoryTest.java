package com.fooddelivery.gateway.filter;

import com.fooddelivery.gateway.ratelimit.FixedWindowRateLimiter;
import com.fooddelivery.gateway.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of the {@code RateLimit} filter with mock exchanges, a limit of 2 per minute and a fixed clock. The chain
 * records whether the request reached the service behind the gateway.
 */
class RateLimitGatewayFilterFactoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T08:00:00Z"), ZoneOffset.UTC);
    private static final String CLIENT = "203.0.113.7";

    @Test
    void requestsWithinTheLimitReachTheService() {
        GatewayFilter filter = filter(0);

        assertThat(run(filter, login(CLIENT)).forwarded()).isTrue();
        assertThat(run(filter, login(CLIENT)).forwarded()).isTrue();
    }

    @Test
    void requestOverTheLimitGets429WithRetryAfterAndNeverReachesTheService() {
        GatewayFilter filter = filter(0);
        run(filter, login(CLIENT));
        run(filter, login(CLIENT));

        Result result = run(filter, login(CLIENT));

        assertThat(result.forwarded()).isFalse();
        assertThat(result.exchange().getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(result.exchange().getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        JsonNode body = JSON.readTree(result.exchange().getResponse().getBodyAsString().block());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("code").asString()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(body.get("path").asString()).isEqualTo("/auth/login");
    }

    @Test
    void eachClientIpHasItsOwnLimit() {
        GatewayFilter filter = filter(0);
        run(filter, login(CLIENT));
        run(filter, login(CLIENT));

        assertThat(run(filter, login("198.51.100.9")).forwarded()).isTrue();
    }

    @Test
    void eachPathHasItsOwnLimit() {
        GatewayFilter filter = filter(0);
        run(filter, login(CLIENT));
        run(filter, login(CLIENT));

        assertThat(run(filter, post("/auth/register", CLIENT)).forwarded()).isTrue();
    }

    @Test
    void forwardedForIsIgnoredWithoutTrustedProxies() {
        GatewayFilter filter = filter(0);
        run(filter, login(CLIENT).header("X-Forwarded-For", "1.1.1.1"));
        run(filter, login(CLIENT).header("X-Forwarded-For", "2.2.2.2"));

        assertThat(run(filter, login(CLIENT).header("X-Forwarded-For", "3.3.3.3")).forwarded()).isFalse();
    }

    @Test
    void behindATrustedProxyTheForwardedClientIpIsLimited() {
        GatewayFilter filter = filter(1);
        String proxy = "10.0.0.5";
        run(filter, login(proxy).header("X-Forwarded-For", CLIENT));
        run(filter, login(proxy).header("X-Forwarded-For", CLIENT));

        assertThat(run(filter, login(proxy).header("X-Forwarded-For", CLIENT)).forwarded()).isFalse();
        assertThat(run(filter, login(proxy).header("X-Forwarded-For", "198.51.100.9")).forwarded()).isTrue();
    }

    private static GatewayFilter filter(int trustedProxyHops) {
        RateLimitProperties properties = new RateLimitProperties(2, Duration.ofMinutes(1), trustedProxyHops);
        return new RateLimitGatewayFilterFactory(new FixedWindowRateLimiter(properties, CLOCK), JSON, properties)
                .apply(new RateLimitGatewayFilterFactory.Config());
    }

    private static MockServerHttpRequest.BaseBuilder<?> login(String remoteIp) {
        return post("/auth/login", remoteIp);
    }

    private static MockServerHttpRequest.BaseBuilder<?> post(String path, String remoteIp) {
        return MockServerHttpRequest.post(path).remoteAddress(new InetSocketAddress(remoteIp, 54321));
    }

    private static Result run(GatewayFilter filter, MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean forwarded = new AtomicBoolean();
        filter.filter(exchange, forwardedExchange -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();
        return new Result(exchange, forwarded.get());
    }

    private record Result(MockServerWebExchange exchange, boolean forwarded) {
    }
}
