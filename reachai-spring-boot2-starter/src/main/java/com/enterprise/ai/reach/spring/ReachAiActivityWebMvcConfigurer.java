package com.enterprise.ai.reach.spring;

import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the 主动感知 interceptor for every Spring MVC handler. */
public class ReachAiActivityWebMvcConfigurer implements WebMvcConfigurer {

    private final ReachAiActivityInterceptor interceptor;

    public ReachAiActivityWebMvcConfigurer(ReachAiActivityInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addWebRequestInterceptor(interceptor);
    }
}
