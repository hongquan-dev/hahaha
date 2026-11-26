package com.fooddelivery.gateway.filter;

import com.fooddelivery.dto.requestid.RequestIds;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gives every request through the gateway a request ID in the {@code X-Request-Id} header ({@code RequestIds} of
 * common-dto-lib), sent to the service behind it and returned to the client, so the log lines of every service the
 * request goes through can be found by one ID. Runs first, before the route filters, so a request rejected by
 * {@code JwtAuth} or {@code RateLimit} has it too.
 *
 * <p>The client's ID is kept if valid (e.g. sent by the frontend to match its own logs), otherwise a new one replaces
 * it. The response header is set when the response is committed, over the one the service returned, so the client
 * always gets exactly one value. The gateway itself is reactive and has no {@code ThreadContext}: its log lines read
 * the ID from the request header.
 */
@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = RequestIds.orGenerate(exchange.getRequest().getHeaders().getFirst(RequestIds.HEADER));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(RequestIds.HEADER, requestId))
                .build();
        ServerHttpResponse response = exchange.getResponse();
        response.beforeCommit(() -> {
            response.getHeaders().set(RequestIds.HEADER, requestId);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request).build());
    }

    // Before every other filter of the gateway, including the route filters
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
