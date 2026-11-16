package com.fooddelivery.auth.controller;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.auth.config.properties.AuthProperties;
import com.fooddelivery.auth.dto.LoginRequest;
import com.fooddelivery.auth.dto.RegisterRequest;
import com.fooddelivery.auth.dto.UserDto;
import com.fooddelivery.auth.service.auth.AuthService;
import com.fooddelivery.auth.service.auth.LoginResult;
import com.fooddelivery.auth.service.session.IssuedTokens;
import com.fooddelivery.auth.service.session.SessionService;
import com.fooddelivery.auth.web.AuthCookies;
import com.fooddelivery.dto.response.ApiResponse;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.web.exception.BusinessException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AuthController} with mocked services and real {@link AuthCookies} on a fixed clock: each
 * endpoint reads the right values from the {@link ApiRequest}, calls the right service, and answers with the right
 * status, body and {@code Set-Cookie} headers. The rules of the validators and services are tested elsewhere.
 */
class AuthControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-06T08:00:00Z");
    private static final UserDto USER = new UserDto(17939477151001L, "quan", "quan@gmail.com", "+84912345678",
            "CUSTOMER", true, LocalDateTime.parse("2026-11-06T15:00:00"));
    private static final IssuedTokens TOKENS = new IssuedTokens("new-access", "new-refresh",
            NOW.plus(Duration.ofDays(7)), NOW.plus(Duration.ofDays(30)));

    private final AuthService authService = mock(AuthService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final AuthController controller = new AuthController(authService, sessionService, authCookies());

    @Test
    void registerPassesTheBodyAsSentAndReturnsCreatedWithoutCookies() {
        RegisterRequest body = new RegisterRequest(" Quan ", "Abc@1234", "Quan@Gmail.com", "0912.345.678");
        when(authService.register(body)).thenReturn(USER);

        ResponseEntity<ApiResponse<UserDto>> response = controller.register(request("POST", "/auth/register",
                "{\"username\":\" Quan \",\"password\":\"Abc@1234\",\"email\":\"Quan@Gmail.com\","
                        + "\"phoneNumber\":\"0912.345.678\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().data()).isEqualTo(USER);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void loginPassesBodyUserAgentAndIpAndSetsBothCookies() {
        when(authService.login(new LoginRequest("quan", "Abc@1234"), "Mozilla/5.0", "10.0.0.1"))
                .thenReturn(new LoginResult(USER, TOKENS));
        MockHttpServletRequest servletRequest = servletRequest("POST", "/auth/login");
        servletRequest.addHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0");

        ResponseEntity<ApiResponse<UserDto>> response = controller.login(
                apiRequest(servletRequest, "{\"identifier\":\"quan\",\"password\":\"Abc@1234\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data()).isEqualTo(USER);
        assertTokenCookiesSet(response);
    }

    @Test
    void loginWithoutUserAgentPassesNull() {
        when(authService.login(any(), any(), any())).thenReturn(new LoginResult(USER, TOKENS));

        controller.login(request("POST", "/auth/login", "{\"identifier\":\"quan\",\"password\":\"Abc@1234\"}"));

        verify(authService).login(new LoginRequest("quan", "Abc@1234"), null, "10.0.0.1");
    }

    @Test
    void loginBodyNeverCarriesTheTokens() {
        when(authService.login(any(), any(), any())).thenReturn(new LoginResult(USER, TOKENS));

        ResponseEntity<ApiResponse<UserDto>> response = controller.login(
                request("POST", "/auth/login", "{\"identifier\":\"quan\",\"password\":\"Abc@1234\"}"));

        assertThat(response.getBody().toString()).doesNotContain("new-access", "new-refresh");
    }

    @Test
    void meReadsTheAccessTokenCookie() {
        when(authService.currentUser("abc.def.ghi")).thenReturn(USER);
        MockHttpServletRequest servletRequest = servletRequest("GET", "/auth/me");
        servletRequest.setCookies(new Cookie(AuthCookies.ACCESS_TOKEN_COOKIE, "abc.def.ghi"));

        ResponseEntity<ApiResponse<UserDto>> response = controller.me(apiRequest(servletRequest, ""));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data()).isEqualTo(USER);
    }

    @Test
    void meWithoutCookiePassesNull() {
        when(authService.currentUser(null)).thenReturn(USER);

        assertThat(controller.me(request("GET", "/auth/me", "")).getBody().data()).isEqualTo(USER);
    }

    @Test
    void refreshRotatesTheCookieTokenAndSetsBothCookies() {
        when(sessionService.refresh("old-refresh", "Mozilla/5.0", "10.0.0.1")).thenReturn(TOKENS);
        MockHttpServletRequest servletRequest = servletRequest("POST", "/auth/refresh");
        servletRequest.setCookies(new Cookie(AuthCookies.REFRESH_TOKEN_COOKIE, "old-refresh"));
        servletRequest.addHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0");

        ResponseEntity<ApiResponse<Void>> response = controller.refresh(apiRequest(servletRequest, ""));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).isNull();
        assertTokenCookiesSet(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFRESH_TOKEN_INVALID", "REFRESH_TOKEN_REUSED"})
    void refreshUnauthorizedReturnsTheErrorAndClearsBothCookies(String code) {
        when(sessionService.refresh(any(), any(), any()))
                .thenThrow(new BusinessException(ApiStatus.UNAUTHORIZED, code, "Session has ended"));

        ResponseEntity<ApiResponse<Void>> response = controller.refresh(request("POST", "/auth/refresh", ""));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().code()).isEqualTo(code);
        assertCookiesCleared(response);
    }

    @Test
    void refreshConflictIsRethrownAndLeavesTheCookiesAlone() {
        BusinessException conflict = new BusinessException(ApiStatus.CONFLICT, "REFRESH_CONFLICT", "Refreshed already");
        when(sessionService.refresh(any(), any(), any())).thenThrow(conflict);

        assertThatThrownBy(() -> controller.refresh(request("POST", "/auth/refresh", ""))).isSameAs(conflict);
    }

    @Test
    void logoutEndsTheCookieSessionAndClearsBothCookies() {
        MockHttpServletRequest servletRequest = servletRequest("POST", "/auth/logout");
        servletRequest.setCookies(new Cookie(AuthCookies.REFRESH_TOKEN_COOKIE, "old-refresh"));

        ResponseEntity<ApiResponse<Void>> response = controller.logout(apiRequest(servletRequest, ""));

        verify(sessionService).end("old-refresh");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().success()).isTrue();
        assertCookiesCleared(response);
    }

    @Test
    void logoutWithoutCookieStillSucceedsAndClearsBothCookies() {
        ResponseEntity<ApiResponse<Void>> response = controller.logout(request("POST", "/auth/logout", ""));

        verify(sessionService).end(null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertCookiesCleared(response);
    }

    private static void assertTokenCookiesSet(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).hasSize(2);
        assertThat(cookies).anySatisfy(cookie -> assertThat(cookie).startsWith("access_token=new-access;"));
        assertThat(cookies).anySatisfy(cookie -> assertThat(cookie).startsWith("refresh_token=new-refresh;"));
    }

    private static void assertCookiesCleared(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).hasSize(2);
        assertThat(cookies).anySatisfy(cookie -> assertThat(cookie).startsWith("access_token=;").contains("Max-Age=0"));
        assertThat(cookies).anySatisfy(cookie -> assertThat(cookie).startsWith("refresh_token=;").contains("Max-Age=0"));
    }

    private static ApiRequest request(String method, String path, String body) {
        return apiRequest(servletRequest(method, path), body);
    }

    private static MockHttpServletRequest servletRequest(String method, String path) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method, path);
        servletRequest.setRemoteAddr("10.0.0.1");
        return servletRequest;
    }

    private static ApiRequest apiRequest(MockHttpServletRequest servletRequest, String body) {
        return new ApiRequest(servletRequest, Map.of(), body);
    }

    private static AuthCookies authCookies() {
        AuthProperties properties = new AuthProperties(
                new AuthProperties.Jwt("unused", "unused", "kid", "fd-auth-service", Duration.ofMinutes(15)),
                new AuthProperties.Refresh(Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(10)),
                new AuthProperties.Cookie(true),
                List.of("http://localhost:5173"));
        return new AuthCookies(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
