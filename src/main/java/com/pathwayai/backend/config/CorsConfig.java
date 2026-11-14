package com.pathwayai.backend.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Explicit-origin CORS for the read-only public API. Only GET/HEAD/OPTIONS, no credentials, no wildcard origin.
 * Origins come from {@code app.cors.allowed-origins} (env {@code CORS_ALLOWED_ORIGINS}, comma separated).
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        if (allowedOrigins.stream().anyMatch(o -> o.contains("*"))) {
            throw new IllegalStateException("app.cors.allowed-origins must list explicit origins; wildcards are not allowed");
        }
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "HEAD", "OPTIONS")
                .allowedHeaders("Accept", "Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
