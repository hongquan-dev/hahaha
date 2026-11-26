package com.fooddelivery.gateway.filter;

import com.fooddelivery.dto.requestid.RequestIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link RequestIdGlobalFilter} with mock exchanges: the {@code X-Request-Id} the service behind the
 * gateway receives, and the one the client gets back once the response is committed.
 */
class RequestIdGlobalFilterTest {

    private static final String REQUEST_ID = "3f2a9c1e-7b4d-4e0a-9f1c-2d6e8b5a7c30";

    private final RequestIdGlobalFilter filter = new RequestIdGlobalFilter();
    // X-Request-Id headers of the request the chain received, i.e. what the service gets
    private final AtomicReference<List<String>> forwarded = new AtomicReference<>();

    @Test
    void validIdOfTheClientIsForwardedAndReturned() {
        MockServerWebExchange exchange = run(REQUEST_ID);

        assertThat(forwarded.get()).containsExactly(REQUEST_ID);
        assertThat(exchange.getResponse().getHeaders().get(RequestIds.HEADER)).containsExactly(REQUEST_ID);
    }

    @Test
    void requestWithoutIdGetsANewOne() {
        MockServerWebExchange exchange = run(null);

        assertThat(forwarded.get()).singleElement().satisfies(id -> assertThat(RequestIds.isValid(id)).isTrue());
        assertThat(exchange.getResponse().getHeaders().get(RequestIds.HEADER)).isEqualTo(forwarded.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "a=b"})
    void invalidIdIsReplaced(String invalid) {
        run(invalid);

        assertThat(forwarded.get()).singleElement().isNotEqualTo(invalid)
                .satisfies(id -> assertThat(RequestIds.isValid(id)).isTrue());
    }

    @Test
    void clientGetsOneIdEvenIfTheServiceReturnedItsOwn() {
        MockServerWebExchange exchange = MockServerWebExchange.from(request(REQUEST_ID));

        filter.filter(exchange, routed -> {
            routed.getResponse().getHeaders().add(RequestIds.HEADER, "copied-from-the-service");
            return routed.getResponse().setComplete();
        }).block();

        assertThat(exchange.getResponse().getHeaders().get(RequestIds.HEADER)).containsExactly(REQUEST_ID);
    }

    @Test
    void runsBeforeEveryOtherFilter() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    // Runs the filter on a request with this X-Request-Id (none if null) and commits the response, as the route does
    private MockServerWebExchange run(String requestId) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request(requestId));
        filter.filter(exchange, routed -> {
            forwarded.set(routed.getRequest().getHeaders().get(RequestIds.HEADER));
            return routed.getResponse().setComplete();
        }).then(Mono.defer(() -> exchange.getResponse().setComplete())).block();
        return exchange;
    }

    private static MockServerHttpRequest.BaseBuilder<?> request(String requestId) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/order/health");
        if (requestId != null) {
            request.header(RequestIds.HEADER, requestId);
        }
        return request;
    }
}
