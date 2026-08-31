package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.internalauth.CachedBodyHttpServletRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class ManagedWorkerBodyLimitFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/internal/runtime/managed-worker/";

    private final ManagedExecutorProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request == null ? "" : request.getRequestURI();
        String context = request == null ? "" : request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return !uri.startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isArtifactUpload(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            byte[] body = CachedBodyHttpServletRequest.readCapped(
                    request, properties.maxWorkerRequestBytes());
            filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
        } catch (IOException tooLarge) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"success\":false,\"code\":\"MANAGED_WORKER_REQUEST_TOO_LARGE\","
                            + "\"message\":\"Managed Executor worker request exceeds the byte limit\"}");
        }
    }

    private boolean isArtifactUpload(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        if (!"PUT".equalsIgnoreCase(request.getMethod())) return false;
        String artifactPrefix = PREFIX + "executions/";
        if (!uri.startsWith(artifactPrefix)) return false;
        String[] segments = uri.substring(artifactPrefix.length()).split("/", -1);
        return segments.length == 3
                && !segments[0].isBlank()
                && "artifacts".equals(segments[1])
                && !segments[2].isBlank();
    }
}
