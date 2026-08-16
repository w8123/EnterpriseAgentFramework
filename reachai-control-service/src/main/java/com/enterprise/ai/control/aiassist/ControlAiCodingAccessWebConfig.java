package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.identity.PlatformConsoleRoutePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class ControlAiCodingAccessWebConfig implements WebMvcConfigurer {

    private final ControlAiCodingAccessInterceptor aiCodingAccessInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(aiCodingAccessInterceptor)
                .addPathPatterns(PlatformConsoleRoutePolicy.AI_CODING_KEY_PATH_PATTERNS.toArray(new String[0]));
    }
}
