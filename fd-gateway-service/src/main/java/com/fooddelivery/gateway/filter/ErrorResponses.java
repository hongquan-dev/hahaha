package com.fooddelivery.gateway.filter;

import com.fooddelivery.dto.response.ApiResponse;
import com.fooddelivery.dto.response.ApiStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Error answers written by the gateway's own filters, in the {@code ApiResponse} format of the services' errors, so a
 * client handles a 401 from {@code JwtAuth} or a 429 from {@code RateLimit} like any other error.
 *
 * <p>Ends the exchange: the service behind the route never sees the request. Stateless.
 */
final class ErrorResponses {

    private ErrorResponses() {}

    // Writes the status and an ApiResponse error body with the request path, e.g. 429 "TOO_MANY_REQUESTS"
    static Mono<Void> write(ServerWebExchange exchange, JsonMapper jsonMapper, ApiStatus status, String code,
                            String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(status.value()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        ApiResponse<Void> body = ApiResponse.<Void>error(code, message, exchange.getRequest().getPath().value())
                .withStatus(status);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(jsonMapper.writeValueAsBytes(body))));
    }
}
