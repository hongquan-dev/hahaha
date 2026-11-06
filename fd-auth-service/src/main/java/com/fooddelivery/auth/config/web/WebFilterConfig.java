package com.fooddelivery.auth.config.web;

import com.fooddelivery.apiflow.web.response.ApiResponseWriter;
import com.fooddelivery.auth.config.properties.AuthProperties;
import com.fooddelivery.auth.web.OriginCheckFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Servlet filters of the service, registered here rather than as {@code @Component} so their order is explicit.
 */
@Configuration
public class WebFilterConfig {

    // Runs before RequestCachingFilter of the API flow (HIGHEST_PRECEDENCE + 10), so a rejected request is answered
    // before its body is read
    @Bean
    public FilterRegistrationBean<OriginCheckFilter> originCheckFilter(AuthProperties properties,
                                                                       ApiResponseWriter apiResponseWriter) {
        FilterRegistrationBean<OriginCheckFilter> registration = new FilterRegistrationBean<>(
                new OriginCheckFilter(properties.allowedOrigins(), apiResponseWriter));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }
}
