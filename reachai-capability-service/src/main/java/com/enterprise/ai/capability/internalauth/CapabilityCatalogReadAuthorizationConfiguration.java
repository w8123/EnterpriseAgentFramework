package com.enterprise.ai.capability.internalauth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CapabilityCatalogReadAuthorizationConfiguration implements WebMvcConfigurer {
    private final CapabilityCatalogReadAuthorizationInterceptor authorization;

    public CapabilityCatalogReadAuthorizationConfiguration(CapabilityCatalogReadAuthorizationInterceptor authorization) {
        this.authorization = authorization;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Inspect the canonical path even when MVC accepted encoded/matrix path variants.
        registry.addInterceptor(authorization).order(-100).addPathPatterns("/**");
    }
}
