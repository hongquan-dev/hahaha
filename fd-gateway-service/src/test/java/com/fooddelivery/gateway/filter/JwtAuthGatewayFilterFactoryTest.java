package com.fooddelivery.gateway.filter;

import com.fooddelivery.gateway.auth.AccessTokenVerifier;
import com.fooddelivery.gateway.auth.AccessTokenVerifierTestSupport;
import com.fooddelivery.gateway.auth.JwksUnavailableException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of the {@code JwtAuth} filter with mock exchanges. The chain records the request it receives, i.e. what
 * the service behind the gateway would see; a rejected request never reaches it.
 */
class JwtAuthGatewayFilterFactoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String validToken;
    private static String expiredToken;

    @BeforeAll
    static void createTokens() {
        validToken = AccessTokenVerifierTestSupport.validToken();
        expiredToken = AccessTokenVerifierTestSupport.expiredToken();
    }

    @Test
    void validCookieReachesTheServiceWithIdentityHeaders() {
        Result result = run(filter(), request("/order/test").cookie(cookie(validToken)));

        assertThat(result.forwarded()).isNotNull();
        assertThat(result.forwarded().getHeaders().getFirst(JwtAuthGatewayFilterFactory.USER_ID_HEADER))
                .isEqualTo("17939477151001");
        assertThat(result.forwarded().getHeaders().getFirst(JwtAuthGatewayFilterFactory.USER_ROLE_HEADER))
                .isEqualTo("CUSTOMER");
    }

    @Test
    void bearerHeaderIsAcceptedWhenThereIsNoCookie() {
        Result result = run(filter(), request("/order/test").header(HttpHeaders.AUTHORIZATION, "bearer " + validToken));

        assertThat(result.forwarded()).isNotNull();
        assertThat(result.forwarded().getHeaders().getFirst(JwtAuthGatewayFilterFactory.USER_ID_HEADER))
                .isEqualTo("17939477151001");
    }

    @Test
    void identityHeadersSentByTheClientAreReplaced() {
        Result result = run(filter(), request("/order/test").cookie(cookie(validToken))
                .header(JwtAuthGatewayFilterFactory.USER_ID_HEADER, "999")
                .header(JwtAuthGatewayFilterFactory.USER_ROLE_HEADER, "ADMIN"));

        assertThat(result.forwarded().getHeaders().get(JwtAuthGatewayFilterFactory.USER_ID_HEADER))
                .containsExactly("17939477151001");
        assertThat(result.forwarded().getHeaders().get(JwtAuthGatewayFilterFactory.USER_ROLE_HEADER))
                .containsExactly("CUSTOMER");
    }

    @Test
    void missingTokenIsRejectedWith401Unauthenticated() {
        Result result = run(filter(), request("/order/test"));

        assertRejected(result, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(result.body().get("path").asString()).isEqualTo("/order/test");
    }

    @Test
    void forgedTokenIsRejectedWith401Unauthenticated() {
        Result result = run(filter(), request("/order/test").cookie(cookie(validToken + "x")));

        assertRejected(result, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void expiredTokenIsRejectedWith401TokenExpired() {
        Result result = run(filter(), request("/order/test").cookie(cookie(expiredToken)));

        assertRejected(result, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED");
    }

    @Test
    void keysThatCannotBeFetchedGive503() {
        AccessTokenVerifier verifier = AccessTokenVerifierTestSupport.verifier(
                keyId -> Mono.error(new JwksUnavailableException("down", new RuntimeException())));

        Result result = run(filter(verifier), request("/order/test").cookie(cookie(validToken)));

        assertRejected(result, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE");
    }

    @Test
    void publicPathNeedsNoTokenButStillDropsIdentityHeaders() {
        Result result = run(filter(), request("/order/health")
                .header(JwtAuthGatewayFilterFactory.USER_ID_HEADER, "999"));

        assertThat(result.forwarded()).isNotNull();
        assertThat(result.forwarded().getHeaders().containsHeader(JwtAuthGatewayFilterFactory.USER_ID_HEADER))
                .isFalse();
    }

    private static GatewayFilter filter() {
        return filter(AccessTokenVerifierTestSupport.verifier());
    }

    private static GatewayFilter filter(AccessTokenVerifier verifier) {
        return new JwtAuthGatewayFilterFactory(verifier, JSON, AccessTokenVerifierTestSupport.properties())
                .apply(new JwtAuthGatewayFilterFactory.Config());
    }

    private static MockServerHttpRequest.BaseBuilder<?> request(String path) {
        return MockServerHttpRequest.get(path);
    }

    private static HttpCookie cookie(String token) {
        return new HttpCookie(JwtAuthGatewayFilterFactory.ACCESS_TOKEN_COOKIE, token);
    }

    // Runs the filter; forwarded is the request the chain received, null if the filter answered itself
    private static Result run(GatewayFilter filter, MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
        filter.filter(exchange, forwardedExchange -> {
            forwarded.set(forwardedExchange.getRequest());
            return Mono.empty();
        }).block();

        // The mock response refuses to be read when nothing was written, i.e. when the request was forwarded
        if (forwarded.get() != null) {
            return new Result(forwarded.get(), exchange.getResponse().getStatusCode(), null);
        }
        String body = exchange.getResponse().getBodyAsString().block();
        return new Result(null, exchange.getResponse().getStatusCode(), JSON.readTree(body));
    }

    private static void assertRejected(Result result, HttpStatus status, String code) {
        assertThat(result.forwarded()).isNull();
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.body().get("success").asBoolean()).isFalse();
        assertThat(result.body().get("status").asInt()).isEqualTo(status.value());
        assertThat(result.body().get("code").asString()).isEqualTo(code);
    }

    private record Result(ServerHttpRequest forwarded, HttpStatusCode status, JsonNode body) {
    }
}
