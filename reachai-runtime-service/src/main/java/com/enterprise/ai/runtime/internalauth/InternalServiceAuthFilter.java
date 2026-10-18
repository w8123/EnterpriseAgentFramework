package com.enterprise.ai.runtime.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * Fail-closed HMAC gate for Control→Runtime Agent execute (sync + SSE).
 * Caches a size-capped body so digest verification and controllers share identical bytes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class InternalServiceAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/internal/runtime/capability-references",
            "/internal/runtime/workflows/studio/read-only-trials",
            "/internal/runtime/agents/execute",
            "/internal/runtime/agents/execute/stream");

    private static final java.util.regex.Pattern AGENT_GRAPH_SYNC_PATH =
            java.util.regex.Pattern.compile("^/api/registry/projects/([^/]+)/agent-graphs/sync$");

    private final InternalServiceAuthVerifier verifier;
    private final InternalServiceAuthProperties properties;

    public InternalServiceAuthFilter(InternalServiceAuthVerifier verifier,
                                     InternalServiceAuthProperties properties) {
        this.verifier = verifier;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = normalizePath(request);
        return !PROTECTED_PATHS.contains(path)
                && !isDebugSessionPath(path)
                && !isWorkflowReleasePath(path)
                && !path.equals("/internal/runtime/a2a/executions")
                && !(path.startsWith("/internal/runtime/a2a/executions/")
                && path.endsWith(":cancel"))
                && !path.startsWith("/internal/runtime/managed-executions")
                && !path.startsWith("/internal/runtime/automations")
                && !path.startsWith("/internal/runtime/session-retention/")
                && !path.startsWith("/internal/runtime/mcp/tool-executions")
                && !isConsoleCapabilityInvocationPath(path)
                && !isHttpApiConsolePath(path)
                && !isRunOpsReplayPath(path)
                && agentGraphSyncProjectCode(path) == null
                && !("DELETE".equalsIgnoreCase(request.getMethod())
                && path.startsWith("/internal/runtime/agents/sessions/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = normalizePath(request);
        byte[] bodyBytes;
        try {
            bodyBytes = CachedBodyHttpServletRequest.readCapped(request, properties.maxBodyBytes());
        } catch (IOException ex) {
            writeUnauthorized(response);
            return;
        }
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request, bodyBytes);
        Optional<VerifiedInternalServiceAuth> verified = verifier.verify(
                cached.getMethod(),
                path,
                cached.getHeader(InternalServiceAuthHeaders.CALLER),
                cached.getHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                cached.getHeader(InternalServiceAuthHeaders.IDENTITY_TENANT_ID),
                cached.getHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                cached.getHeader(InternalServiceAuthHeaders.TIMESTAMP),
                cached.getHeader(InternalServiceAuthHeaders.NONCE),
                cached.getHeader(InternalServiceAuthHeaders.BODY_SHA256),
                cached.getHeader(InternalServiceAuthHeaders.SIGNATURE),
                bodyBytes,
                System.currentTimeMillis());
        if (verified.isEmpty() || (("/internal/runtime/capability-references".equals(path)
                || "/internal/runtime/workflows/studio/read-only-trials".equals(path) || isWorkflowReleasePath(path)
                || isDebugSessionPath(path) || isConsoleCapabilityInvocationPath(path)
                || isHttpApiConsolePath(path) || isRunOpsReplayPath(path))
                && !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(verified.get().identitySource()))) {
            writeUnauthorized(response);
            return;
        }
        String graphSyncProject = agentGraphSyncProjectCode(path);
        boolean projectCredential =
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL.equals(verified.get().identitySource());
        // A project credential identity is only valid for that project's own Agent Graph sync.
        if ((graphSyncProject != null || projectCredential)
                && (!projectCredential || graphSyncProject == null
                || !graphSyncProject.equals(verified.get().identityTenantId()))) {
            writeUnauthorized(response);
            return;
        }
        cached.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR, verified.get());
        filterChain.doFilter(cached, response);
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"success\":false,\"code\":\"RUNTIME_INTERNAL_AUTH_REQUIRED\","
                        + "\"answer\":\"internal service authentication failed\"}");
    }

    private static boolean isRunOpsReplayPath(String path) {
        return path.startsWith("/internal/runtime/runops/traces/") && path.endsWith("/replay");
    }

    /** SDK Agent Graph sync writes Workflows; Control forwards it only after verifying the project signature. */
    private static String agentGraphSyncProjectCode(String path) {
        if (path == null) {
            return null;
        }
        java.util.regex.Matcher matcher = AGENT_GRAPH_SYNC_PATH.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static boolean isWorkflowReleasePath(String path) {
        return path.matches("/api/workflows/[^/]+/versions/(publish|[^/]+/rollback)");
    }

    private static boolean isDebugSessionPath(String path) {
        return path.equals("/api/runtime/debug-sessions") || path.startsWith("/api/runtime/debug-sessions/");
    }

    private static boolean isConsoleCapabilityInvocationPath(String path) {
        return "/internal/runtime/console-capability-invocations".equals(path)
                || (path != null && path.matches("^/internal/runtime/console-capability-invocations/[0-9a-fA-F-]{36}$"));
    }

    private static boolean isHttpApiConsolePath(String path) {
        return "/internal/runtime/http-api-connections".equals(path)
                || "/internal/runtime/http-api-connections/project-default".equals(path)
                || "/internal/runtime/http-api-connections/override-removals".equals(path)
                || "/internal/runtime/http-api-invocations".equals(path)
                || (path != null && path.matches("^/internal/runtime/http-api-invocations/[0-9a-fA-F-]{36}$"));
    }

    private static String normalizePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        if (uri == null || uri.isBlank()) {
            return "";
        }
        return uri.endsWith("/") && uri.length() > 1 ? uri.substring(0, uri.length() - 1) : uri;
    }
}
