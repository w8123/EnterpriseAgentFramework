package com.enterprise.ai.control.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Binds browser requests to the origin in their verified Embed bearer. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
@RequiredArgsConstructor
public class PlatformEmbedTokenOriginFilter extends OncePerRequestFilter {
    private final PlatformEmbedTokenService tokenService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/embed/") || path.startsWith("/api/embed/token/")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        List<String> origins = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        // Non-browser callers may omit Origin; endpoint authentication still applies.
        if (origins.isEmpty() || !StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            var claims = tokenService.verify(authorization.substring("Bearer ".length()).trim());
            if (origins.size() != 1 || !StringUtils.hasText(origins.get(0))
                    || !origins.get(0).equals(claims.getOrigin())) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Embed token origin mismatch");
                return;
            }
        } catch (PlatformEmbedTokenException invalid) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Embed token");
            return;
        }
        chain.doFilter(request, response);
    }
}
