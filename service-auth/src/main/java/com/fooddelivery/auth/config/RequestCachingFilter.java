package com.fooddelivery.auth.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RequestCachingFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest((HttpServletRequest) request);
        chain.doFilter(wrappedRequest, response);
    }
}
