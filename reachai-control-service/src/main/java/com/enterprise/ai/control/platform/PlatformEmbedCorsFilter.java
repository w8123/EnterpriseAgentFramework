package com.enterprise.ai.control.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * CORS for the embedded chat API. Origins come from the project embed policies
 * ({@link PlatformEmbedOriginPolicy}); {@code reachai.embed.cors.*} only adds
 * extra origins (for example a gateway) when explicitly enabled.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class PlatformEmbedCorsFilter extends OncePerRequestFilter {

    private final PlatformEmbedCorsProperties properties;
    private final PlatformEmbedOriginPolicy originPolicy;
    private final CorsProcessor processor = new DefaultCorsProcessor();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return !path.startsWith("/api/embed/") || !CorsUtils.isCorsRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean valid = processor.processRequest(configuration(), request, response);
        if (!valid || CorsUtils.isPreFlightRequest(request)) {
            return;
        }
        filterChain.doFilter(request, response);
    }

    CorsConfiguration configuration() {
        CorsConfiguration configuration = new PolicyBackedCorsConfiguration();
        configuration.setAllowedMethods(properties.getAllowedMethods());
        configuration.setAllowedHeaders(properties.getAllowedHeaders());
        if (!CollectionUtils.isEmpty(properties.getExposedHeaders())) {
            configuration.setExposedHeaders(properties.getExposedHeaders());
        }
        configuration.setAllowCredentials(properties.getAllowCredentials());
        configuration.setMaxAge(properties.getMaxAgeSeconds());
        return configuration;
    }

    private boolean staticallyAllowed(String origin) {
        if (!properties.isEnabled()) {
            return false;
        }
        CorsConfiguration extra = new CorsConfiguration();
        List<String> patterns = properties.getAllowedOriginPatterns();
        List<String> origins = properties.getAllowedOrigins();
        if (!CollectionUtils.isEmpty(patterns)) {
            extra.setAllowedOriginPatterns(patterns);
        }
        if (!CollectionUtils.isEmpty(origins)) {
            extra.setAllowedOrigins(origins);
        }
        return extra.checkOrigin(origin) != null;
    }

    private class PolicyBackedCorsConfiguration extends CorsConfiguration {
        @Override
        @Nullable
        public String checkOrigin(@Nullable String origin) {
            if (origin == null) {
                return null;
            }
            return originPolicy.allows(origin) || staticallyAllowed(origin) ? origin : null;
        }
    }
}
