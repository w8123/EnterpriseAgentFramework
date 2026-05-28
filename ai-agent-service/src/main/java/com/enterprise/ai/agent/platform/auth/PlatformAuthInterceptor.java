package com.enterprise.ai.agent.platform.auth;

import com.enterprise.ai.agent.governance.GuardDecisionLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PlatformAuthInterceptor implements HandlerInterceptor {

    private final PlatformAuthService authService;
    private final PlatformAuthorizationService authorizationService;
    private final GuardDecisionLogService guardDecisionLogService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if (isPublic(path) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Optional<PlatformPrincipal> principal = authService.authenticate(extractBearer(request));
        if (principal.isEmpty()) {
            auditDeny(request, null, "platform login required");
            writeError(response, 401, "platform login required");
            return false;
        }
        if (!authorizationService.isAllowed(
                principal.get(),
                request.getMethod(),
                path,
                extractProjectCode(request),
                extractProjectId(request))) {
            auditDeny(request, principal.get(), "platform permission denied");
            writeError(response, 403, "platform permission denied");
            return false;
        }
        PlatformAuthContext.set(principal.get());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        PlatformAuthContext.clear();
    }

    private boolean isPublic(String path) {
        if (path == null || !path.startsWith("/api/")) {
            return true;
        }
        return path.equals("/api/platform/auth/login")
                || path.startsWith("/api/embed/")
                || path.startsWith("/api/registry/")
                || path.startsWith("/api/v1/agents/");
    }

    private String extractBearer(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring("Bearer ".length()).trim();
        }
        return null;
    }

    private String extractProjectCode(HttpServletRequest request) {
        String queryValue = request.getParameter("projectCode");
        if (queryValue != null && !queryValue.isBlank()) {
            return queryValue;
        }
        String[] parts = request.getRequestURI().split("/");
        for (int i = 0; i < parts.length - 2; i++) {
            if ("registry".equals(parts[i]) && "projects".equals(parts[i + 1])) {
                return parts[i + 2];
            }
        }
        return null;
    }

    private String extractProjectId(HttpServletRequest request) {
        String queryValue = request.getParameter("projectId");
        if (queryValue != null && !queryValue.isBlank()) {
            return queryValue;
        }
        String[] parts = request.getRequestURI().split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("scan-projects".equals(parts[i])) {
                return parts[i + 1];
            }
        }
        return null;
    }

    private void writeError(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "code", code,
                "message", message
        )));
    }

    private void auditDeny(HttpServletRequest request, PlatformPrincipal principal, String reason) {
        guardDecisionLogService.record(
                null,
                "PLATFORM_RBAC",
                "API",
                request.getRequestURI(),
                "DENY",
                reason,
                Map.of(
                        "method", request.getMethod(),
                        "userId", principal == null ? "" : String.valueOf(principal.userId()),
                        "username", principal == null ? "" : principal.username(),
                        "remoteAddr", request.getRemoteAddr() == null ? "" : request.getRemoteAddr()
                ));
    }
}
