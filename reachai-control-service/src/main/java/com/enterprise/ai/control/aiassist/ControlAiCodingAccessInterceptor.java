package com.enterprise.ai.control.aiassist;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.enterprise.ai.control.identity.PlatformConsoleRoutePolicy;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class ControlAiCodingAccessInterceptor implements HandlerInterceptor {

    private static final String PROJECT_PREFIX = "/api/ai-coding/projects/";
    private static final String WORKFLOW_PREFIX = "/api/workflows/";
    private static final String WORKFLOW_AI_CODING_MARKER = "/ai-coding/";

    private final ControlAiCodingAccessGuard guard;

    public ControlAiCodingAccessInterceptor(@Lazy ControlAiCodingAccessGuard guard) {
        this.guard = guard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        try {
            String path = requestPath(request);
            String key = request.getHeader(ControlAiCodingAccessGuard.AI_CODING_HEADER);
            Long projectId = projectId(path);
            if (projectId != null) {
                guard.requireProjectAccess(projectId, key);
                return true;
            }
            String workflowId = workflowId(path);
            if (StringUtils.hasText(workflowId)) {
                guard.requireWorkflowAccess(workflowId, key);
                return true;
            }
            return true;
        } catch (ResponseStatusException ex) {
            writeError(response, ex);
            return false;
        }
    }

    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private static Long projectId(String path) {
        if (!path.startsWith(PROJECT_PREFIX)) {
            return null;
        }
        String rest = path.substring(PROJECT_PREFIX.length());
        int slash = rest.indexOf('/');
        String projectId = slash < 0 ? rest : rest.substring(0, slash);
        if (!StringUtils.hasText(projectId)) {
            return null;
        }
        try {
            return Long.parseLong(projectId.trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "projectId must be numeric", ex);
        }
    }

    private static String workflowId(String path) {
        if (!path.startsWith(WORKFLOW_PREFIX)) {
            return null;
        }
        String rest = path.substring(WORKFLOW_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String workflowId = rest.substring(0, slash);
        String suffix = rest.substring(slash);
        return suffix.startsWith(WORKFLOW_AI_CODING_MARKER) ? workflowId : null;
    }

    private static void writeError(HttpServletResponse response, ResponseStatusException ex) throws IOException {
        response.setStatus(ex.getStatusCode().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        int status = ex.getStatusCode().value();
        String code = status == 401
                ? "AI_CODING_KEY_REQUIRED"
                : (status == 403 ? "AI_CODING_ACCESS_DENIED" : "AI_CODING_REQUEST_INVALID");
        String nextAction = status == 401
                ? "SET_AI_CODING_KEY"
                : (status == 403 ? "CHECK_PROJECT_AI_CODING_ACCESS" : "CHECK_REQUEST");
        String reason = json(ex.getReason());
        response.getWriter().write("{\"code\":\"" + code
                + "\",\"credentialDomain\":\""
                + PlatformConsoleRoutePolicy.CredentialDomain.INDEPENDENT_PROTOCOL.name()
                + "\",\"nextAction\":\"" + nextAction
                + "\",\"message\":\"" + reason + "\"}");
    }

    private static String json(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ");
    }
}
