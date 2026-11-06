package com.fooddelivery.auth.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing of the service. Only {@code spring-security-crypto} is on the classpath, so this adds the encoder
 * without Spring Security's filter chain.
 */
@Configuration
public class PasswordConfig {

    // BCrypt with the default cost 10 (~100 ms per hash), slow enough to make brute force on a leaked users table
    // expensive. Shared by every place that hashes or checks a password
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
