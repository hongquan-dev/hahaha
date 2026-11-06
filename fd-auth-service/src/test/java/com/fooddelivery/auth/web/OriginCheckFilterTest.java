package com.fooddelivery.auth.web;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fooddelivery.apiflow.web.response.ApiResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link OriginCheckFilter} with mock requests. A request that passes reaches the chain
 * ({@code chain.getRequest()} is set); a rejected one gets 403 and never reaches it.
 */
class OriginCheckFilterTest {

    private final OriginCheckFilter filter = new OriginCheckFilter(List.of("http://localhost:5173"),
            new ApiResponseWriter(JsonMapper.builder().findAndAddModules().build()));

    @Test
    void getFromAnyOriginPasses() throws Exception {
        assertPasses(request("GET", "http://evil.com"));
    }

    @Test
    void postWithoutOriginPasses() throws Exception {
        assertPasses(request("POST", null));
    }

    @Test
    void postFromAllowedOriginPasses() throws Exception {
        assertPasses(request("POST", "http://localhost:5173"));
    }

    @Test
    void originIsComparedCaseInsensitively() throws Exception {
        assertPasses(request("POST", "HTTP://LOCALHOST:5173"));
    }

    @Test
    void stateChangingMethodsFromUnknownOriginAreRejected() throws Exception {
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            assertRejected(request(method, "http://evil.com"));
        }
    }

    @Test
    void sameHostWithAnotherPortIsRejected() throws Exception {
        assertRejected(request("POST", "http://localhost:3000"));
    }

    @Test
    void nullOriginIsRejected() throws Exception {
        assertRejected(request("POST", "null"));
    }

    private static MockHttpServletRequest request(String method, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/auth/register");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    private void assertPasses(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private void assertRejected(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"ORIGIN_NOT_ALLOWED\"", "\"status\":403");
    }
}
