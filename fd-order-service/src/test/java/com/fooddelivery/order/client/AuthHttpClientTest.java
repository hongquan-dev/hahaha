package com.fooddelivery.order.client;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.order.config.AuthClientProperties;
import com.fooddelivery.web.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Tests of {@link AuthHttpClient} against a real local HTTP server standing in for auth-service. Each test sets the
 * status and body the server answers, or a delay longer than the client's timeout.
 */
class AuthHttpClientTest {

    private static final long USER_ID = 17939477151001L;
    private static final Duration TIMEOUT = Duration.ofMillis(300);

    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);
    private final AtomicReference<String> requestedPath = new AtomicReference<>();

    private HttpServer server;
    private AuthHttpClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            try {
                Thread.sleep(delay.get().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } catch (Exception ignored) {
                // The client may have given up already (timeout test)
            }
        });
        server.start();
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = new AuthHttpClient(new AuthClientProperties(url, TIMEOUT), JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsTheUserFromTheDataOfApiResponse() {
        answer(200, """
                {"success":true,"status":200,"code":"SUCCESS","data":{"id":17939477151001,"username":"quan",
                 "phoneNumber":"+84912345678","role":"CUSTOMER","active":true}}""");

        AuthUser user = client.getUser(USER_ID);

        assertThat(requestedPath).hasValue("/internal/users/17939477151001");
        assertThat(user).isEqualTo(new AuthUser(USER_ID, "quan", "+84912345678", "CUSTOMER", true));
    }

    @Test
    void notFoundKeepsTheStatusAndErrorCodeOfAuthService() {
        answer(404, """
                {"success":false,"status":404,"code":"USER_NOT_FOUND","message":"User 1 not found"}""");

        assertError(ApiStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    @Test
    void serverErrorBecomes502() {
        answer(500, """
                {"success":false,"status":500,"code":"INTERNAL_ERROR","message":"boom"}""");

        assertError(ApiStatus.BAD_GATEWAY, "AUTH_CALL_FAILED");
    }

    @Test
    void answerWithoutDataBecomes502() {
        answer(200, "{\"success\":true}");

        assertError(ApiStatus.BAD_GATEWAY, "AUTH_CALL_FAILED");
    }

    @Test
    void slowAnswerBecomes503AfterTheTimeout() {
        answer(200, "{}");
        delay.set(TIMEOUT.multipliedBy(5));

        long start = System.nanoTime();
        assertError(ApiStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(TIMEOUT.multipliedBy(4));
    }

    @Test
    void serverDownBecomes503() {
        server.stop(0);

        assertError(ApiStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE");
    }

    private void answer(int httpStatus, String json) {
        status.set(httpStatus);
        body.set(json);
    }

    private void assertError(ApiStatus expectedStatus, String code) {
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> client.getUser(USER_ID));

        assertThat(e).isNotNull();
        assertThat(e.getStatus()).isEqualTo(expectedStatus);
        assertThat(e.getCode()).isEqualTo(code);
    }
}
