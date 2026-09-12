package com.enterprise.ai.capability.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class CapabilityInternalAuthFilter extends OncePerRequestFilter {

    static final String ENROLLMENT_PATH = "/internal/capability/registry/enrollments";
    static final String PROJECT_REQUEST_VERIFICATION_PATH =
            "/internal/capability/registry/project-requests/verify";
    static final String TOOL_EXECUTION_PREFIX = "/internal/capability/tools/";
    static final String TOOL_EXECUTION_SUFFIX = "/execute";
    static final String CAPABILITY_INVOCATION_PATH = "/internal/capability/invocations";
    private static final String REGISTRY_PREFIX = "/api/registry/";

    private final CapabilityInternalAuthVerifier verifier;
    private final CapabilityInternalAuthProperties properties;

    public CapabilityInternalAuthFilter(CapabilityInternalAuthVerifier verifier,
                                        CapabilityInternalAuthProperties properties) {
        this.verifier = verifier;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = normalizePath(request);
        return !ENROLLMENT_PATH.equals(path)
                && !PROJECT_REQUEST_VERIFICATION_PATH.equals(path)
                && !CAPABILITY_INVOCATION_PATH.equals(path)
                && !isToolExecutionPath(path)
                && !isCapabilityReviewManagementPath(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        byte[] body;
        try {
            body = CapabilityCachedBodyHttpServletRequest.readCapped(request, properties.maxBodyBytes());
        } catch (IOException tooLarge) {
            unauthorized(response);
            return;
        }
        CapabilityCachedBodyHttpServletRequest cached = new CapabilityCachedBodyHttpServletRequest(request, body);
        String path = normalizePath(cached);
        CapabilityVerifiedInternalServiceAuth verified;
        if (ENROLLMENT_PATH.equals(path) || isCapabilityReviewManagementPath(path)) {
            verified = verifier.verify(cached.getMethod(), path,
                    cached.getHeader(InternalServiceAuthHeaders.CALLER),
                    cached.getHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                    cached.getHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                    cached.getHeader(InternalServiceAuthHeaders.TIMESTAMP),
                    cached.getHeader(InternalServiceAuthHeaders.NONCE),
                    cached.getHeader(InternalServiceAuthHeaders.BODY_SHA256),
                    cached.getHeader(InternalServiceAuthHeaders.SIGNATURE), body,
                    System.currentTimeMillis()).orElse(null);
        } else if (PROJECT_REQUEST_VERIFICATION_PATH.equals(path)) {
            verified = verifier.verifyProjectRequestVerification(cached.getMethod(), path,
                    cached.getHeader(InternalServiceAuthHeaders.CALLER),
                    cached.getHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                    cached.getHeader(InternalServiceAuthHeaders.IDENTITY_TENANT_ID),
                    cached.getHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                    cached.getHeader(InternalServiceAuthHeaders.TIMESTAMP),
                    cached.getHeader(InternalServiceAuthHeaders.NONCE),
                    cached.getHeader(InternalServiceAuthHeaders.BODY_SHA256),
                    cached.getHeader(InternalServiceAuthHeaders.SIGNATURE), body,
                    System.currentTimeMillis()).orElse(null);
        } else {
            verified = verifier.verifyToolExecution(cached.getMethod(), path,
                    cached.getHeader(InternalServiceAuthHeaders.CALLER),
                    cached.getHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                    cached.getHeader(InternalServiceAuthHeaders.IDENTITY_TENANT_ID),
                    cached.getHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                    cached.getHeader(InternalServiceAuthHeaders.TIMESTAMP),
                    cached.getHeader(InternalServiceAuthHeaders.NONCE),
                    cached.getHeader(InternalServiceAuthHeaders.BODY_SHA256),
                    cached.getHeader(InternalServiceAuthHeaders.SIGNATURE), body,
                    System.currentTimeMillis()).orElse(null);
        }
        if (verified == null) {
            unauthorized(response);
            return;
        }
        cached.setAttribute(CapabilityVerifiedInternalServiceAuth.REQUEST_ATTR, verified);
        filterChain.doFilter(cached, response);
    }

    static String normalizePath(HttpServletRequest request) {
        String value = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (value != null && contextPath != null && !contextPath.isEmpty()
                && value.startsWith(contextPath)) {
            value = value.substring(contextPath.length());
        }
        try {
            value = value == null ? null : UriUtils.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEncoding) {
            // Keep the raw path. A lookalike internal Tool route remains protected and will fail HMAC.
        }
        return canonicalizeMappedPath(value);
    }

    /**
     * Mirrors the path equivalences accepted by Spring MVC before both route
     * classification and HMAC verification. In particular, Spring removes
     * matrix parameters and may map paths containing duplicate separators or
     * dot segments to the same controller method. Security decisions must use
     * that canonical identity rather than the attacker-controlled raw URI.
     */
    static String canonicalizeMappedPath(String value) {
        if (value == null) {
            return null;
        }
        String slashNormalized = value.replace('\\', '/');
        StringBuilder withoutMatrixParameters = new StringBuilder(slashNormalized.length());
        boolean inMatrixParameter = false;
        for (int index = 0; index < slashNormalized.length(); index++) {
            char current = slashNormalized.charAt(index);
            if (current == ';') {
                inMatrixParameter = true;
                continue;
            }
            if (current == '/') {
                inMatrixParameter = false;
                withoutMatrixParameters.append(current);
                continue;
            }
            if (!inMatrixParameter) {
                withoutMatrixParameters.append(current);
            }
        }

        Deque<String> segments = new ArrayDeque<>();
        for (String segment : withoutMatrixParameters.toString().split("/+")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                continue;
            }
            segments.addLast(segment);
        }
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }

    static boolean isToolExecutionPath(String path) {
        if (path == null || !path.startsWith(TOOL_EXECUTION_PREFIX)
                || !path.endsWith(TOOL_EXECUTION_SUFFIX)) {
            return false;
        }
        String qualifiedName = path.substring(
                TOOL_EXECUTION_PREFIX.length(), path.length() - TOOL_EXECUTION_SUFFIX.length());
        return !qualifiedName.isBlank() && qualifiedName.indexOf('/') < 0;
    }

    static boolean isCapabilityReviewManagementPath(String path) {
        if (path == null || !path.startsWith(REGISTRY_PREFIX)) {
            return false;
        }
        return path.matches("^/api/registry/projects/[^/]+/capabilities/diff$")
                || path.matches("^/api/registry/projects/[^/]+/capability-changes$")
                || path.matches("^/api/registry/projects/[^/]+/capability-snapshots$")
                || path.matches("^/api/registry/projects/[^/]+/capability-snapshots/[0-9]+/diff-items$")
                || path.matches("^/api/registry/projects/[^/]+/capability-diff-items/[0-9]+/(review|rollback)$");
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"CAPABILITY_INTERNAL_AUTH_REQUIRED\",\"message\":\"internal service authentication failed\"}");
    }
}
