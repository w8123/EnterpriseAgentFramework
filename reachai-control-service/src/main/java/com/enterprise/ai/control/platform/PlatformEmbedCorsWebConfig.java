package com.enterprise.ai.control.platform;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class PlatformEmbedCorsWebConfig implements WebMvcConfigurer {

    private final PlatformEmbedCorsProperties properties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!properties.isEnabled()) {
            return;
        }
        CorsRegistration registration = registry.addMapping("/api/embed/**")
                .allowedMethods(properties.getAllowedMethods().toArray(String[]::new))
                .allowedHeaders(properties.getAllowedHeaders().toArray(String[]::new));
        if (!CollectionUtils.isEmpty(properties.getAllowedOriginPatterns())) {
            registration.allowedOriginPatterns(properties.getAllowedOriginPatterns().toArray(String[]::new));
        } else if (!CollectionUtils.isEmpty(properties.getAllowedOrigins())) {
            registration.allowedOrigins(properties.getAllowedOrigins().toArray(String[]::new));
        }
        if (!CollectionUtils.isEmpty(properties.getExposedHeaders())) {
            registration.exposedHeaders(properties.getExposedHeaders().toArray(String[]::new));
        }
        if (properties.getAllowCredentials() != null) {
            registration.allowCredentials(properties.getAllowCredentials());
        }
        if (properties.getMaxAgeSeconds() != null) {
            registration.maxAge(properties.getMaxAgeSeconds());
        }
    }
}
