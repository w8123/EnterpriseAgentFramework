package com.enterprise.ai.agent.platform.auth;

import com.enterprise.ai.agent.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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

    private static final String AI_CODING_KEY_HEADER = "X-ReachAI-AiCoding-Key";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        captureAiCodingKey(request);
        if (isPublic(request) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (isWorkflowAiCodingAccess(path)) {
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
        AiCodingKeyContext.clear();
    }

    private boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/")) {
            return true;
        }
        return path.equals("/api/platform/auth/login")
                || path.startsWith("/api/embed/")
                || path.startsWith("/api/registry/")
                || path.startsWith("/api/v1/agents/")
                || path.startsWith("/api/ai-assist/skills/")
                || isAiCodingManifestAccess(request, path)
                || isAiCodingAgentProvisionAccess(request, path)
                || isAiCodingAccessSessionAccess(request, path)
                || isAiCodingPageAssistantAccess(request, path);
    }

    private boolean isWorkflowAiCodingAccess(String path) {
        return WorkflowAiCodingPaths.matchesRequestPath(path);
    }

    private void captureAiCodingKey(HttpServletRequest request) {
        String headerValue = request.getHeader(AI_CODING_KEY_HEADER);
        if (StringUtils.hasText(headerValue)) {
            AiCodingKeyContext.set(headerValue);
            return;
        }
        String queryValue = request.getParameter("aiCodingKey");
        if (StringUtils.hasText(queryValue)) {
            AiCodingKeyContext.set(queryValue);
        }
    }

    private boolean isAiCodingManifestAccess(HttpServletRequest request, String path) {
        return "GET".equalsIgnoreCase(request.getMethod())
                && path.startsWith("/api/ai-assist/projects/")
                && path.endsWith("/onboarding-manifest")
                && request.getParameter("aiCodingKey") != null;
    }

    private boolean isAiCodingAccessSessionAccess(HttpServletRequest request, String path) {
        if (request.getParameter("aiCodingKey") == null
                || !path.startsWith("/api/ai-assist/projects/")
                || !path.contains("/access-sessions/")) {
            return false;
        }
        return ("GET".equalsIgnoreCase(request.getMethod()) && path.endsWith("/access-sessions/latest"))
                || ("POST".equalsIgnoreCase(request.getMethod()) && path.endsWith("/report"));
    }

    private boolean isAiCodingAgentProvisionAccess(HttpServletRequest request, String path) {
        return request.getParameter("aiCodingKey") != null
                && "POST".equalsIgnoreCase(request.getMethod())
                && path.startsWith("/api/ai-assist/projects/")
                && path.endsWith("/agents/provision");
    }

    private boolean isAiCodingPageAssistantAccess(HttpServletRequest request, String path) {
        if (request.getParameter("aiCodingKey") == null
                || !path.startsWith("/api/ai-assist/projects/")
                || !path.contains("/page-assistant/")) {
            return false;
        }
        return ("GET".equalsIgnoreCase(request.getMethod()) && path.endsWith("/page-assistant/onboarding-manifest"))
                || ("GET".equalsIgnoreCase(request.getMethod()) && path.endsWith("/page-assistant/sessions"))
                || ("GET".equalsIgnoreCase(request.getMethod()) && path.endsWith("/page-assistant/sessions/latest"))
                || ("POST".equalsIgnoreCase(request.getMethod()) && path.endsWith("/page-assistant/pages/register"))
                || ("POST".equalsIgnoreCase(request.getMethod()) && path.endsWith("/report"))
                || ("PUT".equalsIgnoreCase(request.getMethod()) && path.endsWith("/target"))
                || ("POST".equalsIgnoreCase(request.getMethod()) && path.endsWith("/catalog/sync"))
                || ("POST".equalsIgnoreCase(request.getMethod()) && path.endsWith("/checks/run"));
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
