package com.fooddelivery.auth.web;

import com.fooddelivery.apiflow.web.response.ApiResponseWriter;
import com.fooddelivery.web.status.ApiStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CSRF defense on top of the {@code SameSite} cookies: rejects a state-changing request (POST, PUT, PATCH, DELETE) sent
 * by a browser page whose origin is not in {@code auth.allowed-origins}, with 403 {@code ORIGIN_NOT_ALLOWED}.
 *
 * <p>Registered by {@code WebFilterConfig} ahead of every other filter of the service, including the body caching of
 * the API flow, and applied to every path, those excluded from the API flow too. A request without an {@code Origin}
 * header passes: browsers always send it with these methods, so such a request does not come from a browser (Postman,
 * curl, another server) and cannot be a CSRF. {@code Origin: null} (sandboxed iframe, {@code file://} page) is
 * rejected like any other unknown origin. GET, HEAD and OPTIONS are never checked.
 */
public class OriginCheckFilter extends OncePerRequestFilter {
    private static final Logger log = LogManager.getLogger(OriginCheckFilter.class);

    private static final Set<String> CHECKED_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    // Lowercase, e.g. "http://localhost:5173"; scheme and host are case-insensitive
    private final Set<String> allowedOrigins;
    private final ApiResponseWriter apiResponseWriter;

    /**
     * @param allowedOrigins    {@code auth.allowed-origins}, already checked by {@code AuthProperties}
     * @param apiResponseWriter writes the error in the same JSON format as every other response
     */
    public OriginCheckFilter(Collection<String> allowedOrigins, ApiResponseWriter apiResponseWriter) {
        this.allowedOrigins = allowedOrigins.stream()
                .map(origin -> origin.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.apiResponseWriter = apiResponseWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (!CHECKED_METHODS.contains(request.getMethod()) || origin == null
                || allowedOrigins.contains(origin.toLowerCase(Locale.ROOT))) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("[ORIGIN] Rejected [{}] {} from Origin {}", request.getMethod(), request.getRequestURI(), origin);
        apiResponseWriter.writeError(request, response, ApiStatus.FORBIDDEN, "ORIGIN_NOT_ALLOWED",
                "Request origin is not allowed");
    }
}
