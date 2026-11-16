package com.fooddelivery.gateway.auth;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link JwksKeyProvider} against a real local HTTP server standing in for auth-service, which counts the
 * fetches. The refetch limit follows a clock the test moves forward; the cache lifetime is real time, kept short.
 */
class JwksKeyProviderTest {

    private static final Instant START = Instant.parse("2026-11-16T08:00:00Z");
    private static final Duration CACHE_TTL = Duration.ofMillis(300);
    private static final Duration REFETCH_INTERVAL = Duration.ofSeconds(30);

    private static RSAKey oldKey;
    private static RSAKey newKey;

    private HttpServer server;
    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicReference<String> served = new AtomicReference<>();
    private final MutableClock clock = new MutableClock(START);

    @BeforeAll
    static void generateKeys() throws Exception {
        oldKey = new RSAKeyGenerator(2048).keyID("old").generate();
        newKey = new RSAKeyGenerator(2048).keyID("new").generate();
    }

    @BeforeEach
    void startServer() throws Exception {
        serve(oldKey);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            fetches.incrementAndGet();
            byte[] body = served.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/jwk-set+json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchesOnceThenReusesTheKeys() {
        JwksKeyProvider provider = provider();

        RSAKey first = provider.findKey("old").block();
        RSAKey second = provider.findKey("old").block();

        assertThat(first.getModulus()).isEqualTo(oldKey.getModulus());
        assertThat(first.isPrivate()).isFalse();
        assertThat(second).isEqualTo(first);
        assertThat(fetches).hasValue(1);
    }

    @Test
    void fetchesAgainAfterTheCacheExpires() throws Exception {
        JwksKeyProvider provider = provider();
        provider.findKey("old").block();

        Thread.sleep(CACHE_TTL.toMillis() + 200);
        provider.findKey("old").block();

        assertThat(fetches).hasValue(2);
    }

    @Test
    void unknownKidRefetchesAtMostOncePerInterval() {
        JwksKeyProvider provider = provider();
        provider.findKey("old").block();
        serve(oldKey, newKey);

        // Just fetched: a token with an unknown kid does not trigger another fetch
        assertThat(provider.findKey("new").block()).isNull();
        assertThat(fetches).hasValue(1);

        // Interval over: one early fetch, which finds the key auth-service switched to
        clock.advance(REFETCH_INTERVAL.plusSeconds(1));
        assertThat(provider.findKey("new").block().getModulus()).isEqualTo(newKey.getModulus());
        assertThat(provider.findKey("random").block()).isNull();
        assertThat(fetches).hasValue(2);
    }

    @Test
    void concurrentRequestsShareOneFetch() {
        JwksKeyProvider provider = provider();

        List<RSAKey> keys = Flux.range(0, 20).flatMap(i -> provider.findKey("old")).collectList().block();

        assertThat(keys).hasSize(20);
        assertThat(fetches).hasValue(1);
    }

    @Test
    void failsWhenNoKeysWereEverFetched() {
        server.stop(0);

        assertThatThrownBy(() -> provider().findKey("old").block()).isInstanceOf(JwksUnavailableException.class);
    }

    @Test
    void keepsTheLastKeysWhileAuthServiceIsDown() throws Exception {
        JwksKeyProvider provider = provider();
        provider.findKey("old").block();
        server.stop(0);

        Thread.sleep(CACHE_TTL.toMillis() + 200);

        assertThat(provider.findKey("old").block().getModulus()).isEqualTo(oldKey.getModulus());
    }

    private JwksKeyProvider provider() {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
        GatewayAuthProperties properties = new GatewayAuthProperties(uri, "fd-auth-service", CACHE_TTL,
                REFETCH_INTERVAL, Duration.ofSeconds(2), List.of());
        return new JwksKeyProvider(properties, clock);
    }

    // What auth-service publishes from now on: only the public halves, as JwksController does
    private void serve(RSAKey... keys) {
        List<JWK> publicKeys = List.of(keys).stream().<JWK>map(RSAKey::toPublicJWK).toList();
        served.set(new JWKSet(publicKeys).toString());
    }

    // Clock the test moves forward instead of waiting
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
