package com.fooddelivery.auth.controller;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.auth.dto.PingResponse;
import com.fooddelivery.auth.service.test.PingService;
import com.fooddelivery.auth.web.AuthCookies;
import com.fooddelivery.dto.response.ApiResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link TestController} with a mocked {@link PingService}: the {@code access_token} cookie reaches the
 * service and its answer comes back with 200.
 */
class TestControllerTest {

    private static final PingResponse PING = new PingResponse(17939477151001L, "CUSTOMER",
            Instant.parse("2026-11-06T08:00:00Z"), Instant.parse("2026-11-06T08:15:00Z"),
            Instant.parse("2026-11-06T08:05:00Z"));

    private final PingService pingService = mock(PingService.class);
    private final TestController controller = new TestController(pingService);

    @Test
    void pingReadsTheAccessTokenCookieAndReturnsOk() {
        when(pingService.ping("abc.def.ghi")).thenReturn(PING);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/auth/test/ping");
        servletRequest.setCookies(new Cookie(AuthCookies.ACCESS_TOKEN_COOKIE, "abc.def.ghi"));

        ResponseEntity<ApiResponse<PingResponse>> response =
                controller.ping(new ApiRequest(servletRequest, Map.of(), ""));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data()).isEqualTo(PING);
    }

    @Test
    void pingWithoutCookiePassesNull() {
        when(pingService.ping(null)).thenReturn(PING);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/auth/test/ping");

        assertThat(controller.ping(new ApiRequest(servletRequest, Map.of(), "")).getBody().data()).isEqualTo(PING);
    }
}
