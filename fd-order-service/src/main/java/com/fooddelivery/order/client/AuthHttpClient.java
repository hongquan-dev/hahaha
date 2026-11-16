package com.fooddelivery.order.client;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.order.config.AuthClientProperties;
import com.fooddelivery.web.exception.BusinessException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.util.Arrays;

/**
 * Calls the internal HTTP API of auth-service, {@code GET /internal/users/{userId}}, with a blocking {@link RestClient}
 * on the JDK HTTP client. The HTTP counterpart of {@link AuthGrpcClient}: same input, same {@link AuthUser}, same
 * errors, so the caller does not depend on the transport.
 *
 * <p>Every call is bounded by {@code services.auth.timeout} (connect and read). Failures become a
 * {@code BusinessException}:
 * <ul>
 *   <li>A 4xx answer of auth-service (e.g. 404 {@code USER_NOT_FOUND}): the same status and error code.</li>
 *   <li>auth-service down or too slow: 503 {@code AUTH_UNAVAILABLE}.</li>
 *   <li>A 5xx or unreadable answer: 502 {@code AUTH_CALL_FAILED}.</li>
 * </ul>
 */
@Component
public class AuthHttpClient {
    private static final Logger log = LogManager.getLogger(AuthHttpClient.class);

    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public AuthHttpClient(AuthClientProperties properties, JsonMapper jsonMapper) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.httpUrl().toString())
                .requestFactory(requestFactory)
                .build();
        this.jsonMapper = jsonMapper;
    }

    /**
     * Fetches a user from auth-service over HTTP.
     *
     * <p>Throws {@code BusinessException} 404 {@code USER_NOT_FOUND} or 400 {@code INVALID_USER_ID} as answered by
     * auth-service, 503 {@code AUTH_UNAVAILABLE} if it cannot be reached in time, 502 {@code AUTH_CALL_FAILED}
     * otherwise.
     *
     * @param userId ID taken from the {@code X-User-Id} header set by the gateway
     * @return the user, active or locked
     */
    public AuthUser getUser(long userId) {
        try {
            JsonNode body = restClient.get().uri("/internal/users/{userId}", userId).retrieve().body(JsonNode.class);
            JsonNode data = body == null ? null : body.get("data");
            if (data == null || !data.isObject()) {
                log.warn("[AUTH-HTTP] Answer without data for userId={}", userId);
                throw callFailed();
            }
            return jsonMapper.treeToValue(data, AuthUser.class);
        } catch (HttpStatusCodeException e) {
            throw fromErrorResponse(e);
        } catch (ResourceAccessException e) {
            log.warn("[AUTH-HTTP] auth-service unreachable for userId={}: {}", userId, e.getMessage());
            throw new BusinessException(ApiStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE",
                    "Authentication service is unavailable");
        } catch (JacksonException e) {
            log.warn("[AUTH-HTTP] Unreadable answer for userId={}: {}", userId, e.getMessage());
            throw callFailed();
        }
    }

    // A 4xx keeps the status and error code of auth-service, read from its ApiResponse body; a 5xx is auth-service's
    // own failure, reported as 502
    private BusinessException fromErrorResponse(HttpStatusCodeException e) {
        ApiStatus status = apiStatus(e.getStatusCode().value());
        if (e.getStatusCode().is4xxClientError() && status != null) {
            try {
                JsonNode body = jsonMapper.readTree(e.getResponseBodyAsString());
                if (body.hasNonNull("code") && body.hasNonNull("message")) {
                    return new BusinessException(status, body.get("code").asString(), body.get("message").asString());
                }
            } catch (JacksonException parseError) {
                log.debug("[AUTH-HTTP] Unreadable error body: {}", parseError.getMessage());
            }
            log.warn("[AUTH-HTTP] {} without a readable ApiResponse body", e.getStatusCode());
        }
        log.warn("[AUTH-HTTP] auth-service answered {}", e.getStatusCode());
        return callFailed();
    }

    // ApiStatus with this HTTP code, e.g. 404 -> NOT_FOUND; null if ApiStatus has none
    private static ApiStatus apiStatus(int code) {
        return Arrays.stream(ApiStatus.values()).filter(status -> status.value() == code).findFirst().orElse(null);
    }

    private static BusinessException callFailed() {
        return new BusinessException(ApiStatus.BAD_GATEWAY, "AUTH_CALL_FAILED", "Authentication service call failed");
    }
}
