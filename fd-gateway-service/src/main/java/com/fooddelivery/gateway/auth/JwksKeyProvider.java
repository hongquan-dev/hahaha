package com.fooddelivery.gateway.auth;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public keys of auth-service, fetched from its JWKS endpoint with a non-blocking {@link WebClient} and cached, so
 * the gateway checks tokens without calling auth-service on every request.
 *
 * <p>Nothing is fetched at startup: the first request fetches the keys, so the gateway starts even when auth-service
 * is down. Then:
 * <ul>
 *   <li>The keys are reused for {@code jwks-cache-ttl}, then fetched again by the next request.</li>
 *   <li>A token with an unknown {@code kid} (e.g. after auth-service switched to a new key) triggers one early
 *       fetch, at most once per {@code refetch-interval}, so tokens with random {@code kid}s cannot flood
 *       auth-service.</li>
 *   <li>If a fetch fails, the last fetched keys stay in use (retried after {@code refetch-interval}); only when no
 *       keys were ever fetched does the lookup fail with {@link JwksUnavailableException}.</li>
 * </ul>
 * Concurrent requests share one fetch in flight. Thread-safe.
 */
@Component
public class JwksKeyProvider implements PublicKeySource {
    private static final Logger log = LogManager.getLogger(JwksKeyProvider.class);

    private final WebClient webClient;
    private final URI jwksUri;
    private final Duration cacheTtl;
    private final Duration refetchInterval;
    private final Duration timeout;
    private final Clock clock;

    // Fetch in flight or its cached result; replaced to force an early fetch. null until the first request
    private final AtomicReference<Mono<Fetched>> keys = new AtomicReference<>();

    // Keys of the last successful fetch, used while auth-service cannot be reached; null if none succeeded yet
    private volatile JWKSet lastGood;

    private volatile Instant lastFetchStartedAt = Instant.EPOCH;

    public JwksKeyProvider(GatewayAuthProperties properties, Clock clock) {
        this.webClient = WebClient.create();
        this.jwksUri = properties.jwksUri();
        this.cacheTtl = properties.jwksCacheTtl();
        this.refetchInterval = properties.refetchInterval();
        this.timeout = properties.jwksTimeout();
        this.clock = clock;
    }

    @Override
    public Mono<RSAKey> findKey(String keyId) {
        Mono<Fetched> current = current();
        return current.flatMap(fetched -> {
            RSAKey key = rsaKey(fetched.keys(), keyId);
            if (key != null) {
                return Mono.just(key);
            }
            if (clock.instant().isBefore(lastFetchStartedAt.plus(refetchInterval))) {
                return Mono.empty();
            }
            // Only one of the concurrent requests replaces the cache; the others use the fetch it started
            keys.compareAndSet(current, newFetch());
            return keys.get().mapNotNull(refetched -> rsaKey(refetched.keys(), keyId));
        });
    }

    private Mono<Fetched> current() {
        Mono<Fetched> current = keys.get();
        if (current == null) {
            keys.compareAndSet(null, newFetch());
            current = keys.get();
        }
        return current;
    }

    // Lazy: runs when first subscribed, then again each time the cached value expires. Fresh keys are cached for
    // jwks-cache-ttl, the stale fallback only for refetch-interval, an error not at all
    private Mono<Fetched> newFetch() {
        return Mono.defer(() -> {
            lastFetchStartedAt = clock.instant();
            return webClient.get().uri(jwksUri).retrieve().bodyToMono(String.class)
                    .timeout(timeout)
                    .handle((String body, SynchronousSink<JWKSet> sink) -> {
                        try {
                            sink.next(JWKSet.parse(body));
                        } catch (ParseException e) {
                            sink.error(new IllegalStateException("not a JWK Set: " + e.getMessage(), e));
                        }
                    })
                    .map(jwkSet -> {
                        lastGood = jwkSet;
                        log.info("[JWKS] Loaded {} key(s) from {}: kid={}", jwkSet.getKeys().size(), jwksUri,
                                jwkSet.getKeys().stream().map(JWK::getKeyID).toList());
                        return new Fetched(jwkSet, false);
                    })
                    .onErrorResume(e -> fallback(e));
        }).cache(fetched -> fetched.stale() ? refetchInterval : cacheTtl, error -> Duration.ZERO, () -> Duration.ZERO);
    }

    private Mono<Fetched> fallback(Throwable error) {
        JWKSet stale = lastGood;
        if (stale == null) {
            log.warn("[JWKS] Cannot load keys from {}: {}", jwksUri, error.toString());
            return Mono.error(new JwksUnavailableException("Cannot load keys from " + jwksUri, error));
        }
        log.warn("[JWKS] Cannot load keys from {}, using the last loaded keys: {}", jwksUri, error.toString());
        return Mono.just(new Fetched(stale, true));
    }

    // null if the set has no key with this kid, or it is not an RSA key
    private static RSAKey rsaKey(JWKSet jwkSet, String keyId) {
        return jwkSet.getKeyByKeyId(keyId) instanceof RSAKey key ? key : null;
    }

    // stale: the fetch failed and keys holds the last successful fetch instead
    private record Fetched(JWKSet keys, boolean stale) {
    }
}
