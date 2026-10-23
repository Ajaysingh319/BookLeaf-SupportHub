package com.bookleaf.support.config;

import com.bookleaf.support.security.AuthFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows only the configured frontend origin (CLIENT_ORIGIN) to call the API from a browser, with its
 * session cookie. A same-site deployment (the browser reaching the API through the same host) does not need
 * this at all; it is here for a client served from another origin during development.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String allowedOrigin;

    public CorsConfig(AppProperties properties) {
        this.allowedOrigin = properties.cors().allowedOrigin();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", AuthFilter.CSRF_HEADER)
                .allowCredentials(true)
                .exposedHeaders("X-Request-Id", "Retry-After");
    }
}
