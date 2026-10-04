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
    static final String PROJECT_LOOKUP_PREFIX = "/internal/capability/projects/by-id/";
    static final String TOOL_EXECUTION_PREFIX = "/internal/capability/tools/";
    static final String TOOL_EXECUTION_SUFFIX = "/execute";
    static final String CAPABILITY_INVOCATION_PATH = "/internal/capability/invocations";
    static final String CAPABILITY_CATALOG_PATH = "/api/tools";
    static final String CAPABILITY_CATALOG_ITEM_PREFIX = "/api/tools/";
    static final String BUSINESS_METHOD_CATALOG_PATH = "/internal/capability/business-methods";
    static final String BUSINESS_METHOD_CATALOG_ITEM_PREFIX = "/internal/capability/business-methods/";
    static final String HTTP_API_CATALOG_PATH = "/internal/capability/http-apis";
    static final String HTTP_API_CATALOG_ITEM_PREFIX = HTTP_API_CATALOG_PATH + "/";
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
                && !isCapabilityCatalogReadPath(path)
                && !isCapabilityProjectLookupPath(path)
                && !isToolExecutionPath(path)
                && !isHttpApiExecutionPath(path)
                && !isBusinessMethodExecutionPath(path)
                && !isHttpApiAcceptancePath(path)
                && !isApiMarketPath(path)
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
        if (ENROLLMENT_PATH.equals(path) || isCapabilityReviewManagementPath(path)
                || isHttpApiAcceptancePath(path) || isApiMarketPath(path)) {
            verified = verifier.verify(cached.getMethod(), path,
                    cached.getHeader(InternalServiceAuthHeaders.CALLER),
                    cached.getHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                    cached.getHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                    cached.getHeader(InternalServiceAuthHeaders.TIMESTAMP),
                    cached.getHeader(InternalServiceAuthHeaders.NONCE),
                    cached.getHeader(InternalServiceAuthHeaders.BODY_SHA256),
                    cached.getHeader(InternalServiceAuthHeaders.SIGNATURE), body,
                    System.currentTimeMillis()).orElse(null);
        } else if (isCapabilityCatalogReadPath(path) || isCapabilityProjectLookupPath(path)) {
            verified = verifier.verifyCatalogRead(cached.getMethod(), path,
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

    static boolean isCapabilityCatalogReadPath(String path) {
        if (CAPABILITY_CATALOG_PATH.equals(path) || BUSINESS_METHOD_CATALOG_PATH.equals(path)
                || HTTP_API_CATALOG_PATH.equals(path)) {
            return true;
        }
        if (path == null) {
            return false;
        }
        if (path.matches("^/internal/capability/http-apis/[0-9]+$")) return true;
        String prefix = path.startsWith(CAPABILITY_CATALOG_ITEM_PREFIX)
                ? CAPABILITY_CATALOG_ITEM_PREFIX
                : path.startsWith(BUSINESS_METHOD_CATALOG_ITEM_PREFIX)
                        ? BUSINESS_METHOD_CATALOG_ITEM_PREFIX
                        : null;
        if (prefix == null) {
            return false;
        }
        String name = path.substring(prefix.length());
        if (!name.isBlank() && name.indexOf('/') < 0) {
            return true;
        }
        return path.startsWith(BUSINESS_METHOD_CATALOG_ITEM_PREFIX)
                && name.matches("[^/]+/invocation-context");
    }

    static boolean isHttpApiAcceptancePath(String path) {
        return path != null && path.matches("^/internal/capability/http-apis/[0-9]+/accept$");
    }

    static boolean isApiMarketPath(String path) {
        return path != null && (path.equals("/internal/capability/api-market") || path.startsWith("/internal/capability/api-market/")
                || path.equals("/api/api-market") || path.startsWith("/api/api-market/"));
    }

    static boolean isHttpApiExecutionPath(String path) {
        return path != null && path.matches("^/internal/capability/http-apis/[0-9]+/execution-context$");
    }

    static boolean isBusinessMethodExecutionPath(String path) {
        return path != null && path.matches("^/internal/capability/business-methods/[A-Za-z0-9._:-]{1,200}/execution-context$");
    }

    static boolean isCapabilityProjectLookupPath(String path) {
        if (path == null || !path.startsWith(PROJECT_LOOKUP_PREFIX)) {
            return false;
        }
        String projectId = path.substring(PROJECT_LOOKUP_PREFIX.length());
        return !projectId.isBlank() && projectId.indexOf('/') < 0;
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
