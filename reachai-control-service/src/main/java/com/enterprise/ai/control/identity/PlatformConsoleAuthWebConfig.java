package com.enterprise.ai.control.identity;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Authentication boundary for the new AI Coding and page-workbench console
 * routes. External handoff/task protocol routes deliberately do not use this
 * interceptor; they use one-time activation and task-scoped Bearer tokens.
 */
@Configuration
@RequiredArgsConstructor
public class PlatformConsoleAuthWebConfig implements WebMvcConfigurer {

    private final PlatformConsoleAuthInterceptor interceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns(PlatformConsoleRoutePolicy.PROTECTED_PATH_PATTERNS.toArray(new String[0]))
                // /api/workflows/** is a console namespace, but its narrower
                // Workflow AI Coding subresource authenticates with the project key.
                .excludePathPatterns(PlatformConsoleRoutePolicy.INDEPENDENT_PROTOCOL_PATH_PATTERNS.toArray(new String[0]))
                .excludePathPatterns(PlatformConsoleRoutePolicy.EXCLUDED_PATH_PATTERNS.toArray(new String[0]));
    }
}
