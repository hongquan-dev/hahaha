package com.fooddelivery.gateway.filter;

import com.fooddelivery.dto.requestid.RequestIds;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.gateway.auth.AccessTokenVerifier;
import com.fooddelivery.gateway.auth.GatewayAuthProperties;
import com.fooddelivery.gateway.auth.TokenVerification;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Gateway filter {@code JwtAuth}: lets a request reach the service only with a valid access token, and tells the
 * service who the caller is. Added to a route in {@code application.properties}, e.g.
 * <pre>
 * spring.cloud.gateway.server.webflux.routes[1].filters[0]=JwtAuth
 * </pre>
 *
 * <p>At request time, on every request of the route:
 * <ul>
 *   <li>Removes {@link #USER_ID_HEADER} and {@link #USER_ROLE_HEADER} sent by the client, so nobody can claim to be
 *       another user by setting them.</li>
 *   <li>Paths in {@code gateway.auth.public-paths} (e.g. {@code /order/health}) go on without a token.</li>
 *   <li>Reads the token from the {@code access_token} cookie, or else from {@code Authorization: Bearer ...}, and
 *       checks it with {@link AccessTokenVerifier}.</li>
 *   <li>Valid: the request goes on with the two headers set from the {@code sub} and {@code role} claims. Otherwise
 *       it never reaches the service: 401 {@code TOKEN_EXPIRED} (the client refreshes and retries), 401
 *       {@code UNAUTHENTICATED}, or 503 {@code AUTH_UNAVAILABLE}, with an {@code ApiResponse} body like the
 *       services' errors.</li>
 * </ul>
 * The services behind the gateway trust the two headers, which is safe only while their ports are not reachable
 * from outside.
 */
@Component
public class JwtAuthGatewayFilterFactory extends AbstractGatewayFilterFactory<JwtAuthGatewayFilterFactory.Config> {
    private static final Logger log = LogManager.getLogger(JwtAuthGatewayFilterFactory.class);

    // ID of the caller, e.g. "17939477151001", set only after the token was checked
    public static final String USER_ID_HEADER = "X-User-Id";

    // Role of the caller, e.g. "CUSTOMER"; absent if the token has no role claim
    public static final String USER_ROLE_HEADER = "X-User-Role";

    // Same cookie name as AuthCookies.ACCESS_TOKEN_COOKIE of auth-service
    static final String ACCESS_TOKEN_COOKIE = "access_token";

    private static final String BEARER_PREFIX = "Bearer ";

    private final AccessTokenVerifier verifier;
    private final JsonMapper jsonMapper;
    private final List<PathPattern> publicPaths;

    public JwtAuthGatewayFilterFactory(AccessTokenVerifier verifier, JsonMapper jsonMapper,
                                       GatewayAuthProperties properties) {
        super(Config.class);
        this.verifier = verifier;
        this.jsonMapper = jsonMapper;
        this.publicPaths = properties.publicPaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
    }

    @Override
    public GatewayFilter apply(Config config) {
        return this::filter;
    }

    private Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpRequest.Builder forwarded = request.mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.remove(USER_ROLE_HEADER);
                });

        if (isPublic(request)) {
            return chain.filter(exchange.mutate().request(forwarded.build()).build());
        }

        return verifier.verify(readToken(request)).flatMap(result -> {
            log.info("[AUTH] {} {} | requestId={}, result={}, userId={}", request.getMethod(),
                    request.getPath().value(), request.getHeaders().getFirst(RequestIds.HEADER), result.status(),
                    result.userId());
            return switch (result.status()) {
                case VALID -> {
                    forwarded.header(USER_ID_HEADER, String.valueOf(result.userId()));
                    if (result.role() != null) {
                        forwarded.header(USER_ROLE_HEADER, result.role());
                    }
                    yield chain.filter(exchange.mutate().request(forwarded.build()).build());
                }
                case EXPIRED -> reject(exchange, ApiStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Access token has expired");
                case INVALID -> reject(exchange, ApiStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Login required");
                case UNAVAILABLE -> reject(exchange, ApiStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE",
                        "Authentication service is unavailable");
            };
        });
    }

    private boolean isPublic(ServerHttpRequest request) {
        return publicPaths.stream().anyMatch(pattern -> pattern.matches(request.getPath().pathWithinApplication()));
    }

    // The cookie wins over the header, as for the browser, which only sends the cookie; null if neither is present
    private static String readToken(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(ACCESS_TOKEN_COOKIE);
        if (cookie != null && !cookie.getValue().isBlank()) {
            return cookie.getValue();
        }
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    // Ends the exchange here with an ApiResponse error; the service never sees the request
    private Mono<Void> reject(ServerWebExchange exchange, ApiStatus status, String code, String message) {
        return ErrorResponses.write(exchange, jsonMapper, status, code, message);
    }

    /**
     * Arguments of the filter in a route definition. None for now: which paths are public is set once for all routes
     * in {@code gateway.auth.public-paths}.
     */
    public static class Config {
    }
}
