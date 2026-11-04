package com.enterprise.ai.model.security;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.common.internalauth.InternalServiceSecretRing;
import com.enterprise.ai.common.internalauth.ModelServiceRequestAuth;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** All model data and execution require a trusted service; only health probes are public. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ModelInternalAuthFilter extends OncePerRequestFilter {
    private static final Set<String> EXECUTION_PATHS = Set.of(
            "/model/chat", "/model/chat/stream/events", "/model/embedding", "/model/rerank");
    private final List<String> secrets;
    private final long skewMillis;
    private final int maxBodyBytes;
    private final ModelInternalNonceStore nonces;

    public ModelInternalAuthFilter(
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}") String secret,
            @Value("${reachai.internal.accepted-service-secrets:${REACHAI_INTERNAL_SERVICE_ACCEPTED_SECRETS:}}") String acceptedSecrets,
            @Value("${reachai.internal.auth.skew-seconds:300}") long skewSeconds,
            @Value("${reachai.internal.auth.max-body-bytes:33554432}") int maxBodyBytes,
            ModelInternalNonceStore nonces, Environment environment) {
        this.secrets = InternalServiceSecretRing.accepted(secret, acceptedSecrets);
        if (Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile))) {
            InternalServiceSecretRing.requireStrong(secret, secrets);
        }
        this.skewMillis = Math.max(1, Math.min(600, skewSeconds)) * 1000L;
        this.maxBodyBytes = Math.max(1, Math.min(64 * 1024 * 1024, maxBodyBytes));
        this.nonces = nonces;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) && Set.of("/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness").contains(path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Authenticate the claimed digest before buffering any unauthenticated request body.
        if (!verifyHeaders(request)) {
            unauthorized(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);
        if (body.length > maxBodyBytes || !InternalServiceHmac.digestEqualsConstantTime(
                InternalServiceHmac.bodySha256Hex(body), request.getHeader(InternalServiceAuthHeaders.BODY_SHA256))
                || !nonces.tryConsume(request.getHeader(InternalServiceAuthHeaders.CALLER),
                request.getHeader(InternalServiceAuthHeaders.NONCE), Duration.ofMillis(2 * skewMillis))) {
            unauthorized(response);
            return;
        }
        chain.doFilter(new CachedRequest(request, body), response);
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"MODEL_INTERNAL_AUTH_REQUIRED\"}");
    }

    private boolean verifyHeaders(HttpServletRequest request) {
        String caller = request.getHeader(InternalServiceAuthHeaders.CALLER);
        if (secrets.isEmpty() || !allowed(caller, request.getMethod(), path(request))) return false;
        String timestamp = request.getHeader(InternalServiceAuthHeaders.TIMESTAMP);
        String nonce = request.getHeader(InternalServiceAuthHeaders.NONCE);
        String digest = request.getHeader(InternalServiceAuthHeaders.BODY_SHA256);
        String signature = request.getHeader(InternalServiceAuthHeaders.SIGNATURE);
        if (timestamp == null || nonce == null || !nonce.matches("[A-Za-z0-9_-]{16,128}")
                || digest == null || !digest.matches("[0-9a-f]{64}")) return false;
        try {
            long sent = Long.parseLong(timestamp);
            long now = System.currentTimeMillis();
            if (sent < now - skewMillis || sent > now + skewMillis) return false;
        } catch (NumberFormatException invalid) { return false; }
        String target = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        String canonical = ModelServiceRequestAuth.canonical(request.getMethod(), target, caller, timestamp, nonce, digest);
        return InternalServiceHmac.verifyAnyConstantTime(secrets, canonical, signature);
    }

    private static boolean allowed(String caller, String method, String path) {
        if (InternalServiceAuthHeaders.CALLER_CONTROL.equals(caller)) {
            return path.startsWith("/model/") || path.equals("/model") || path.startsWith("/internal/model/");
        }
        if (InternalServiceAuthHeaders.CALLER_RUNTIME.equals(caller)) {
            return ("POST".equals(method) && EXECUTION_PATHS.contains(path))
                    || ("GET".equals(method) && "/internal/model/instances".equals(path));
        }
        return ModelServiceRequestAuth.CALLER_KNOWLEDGE.equals(caller) && "POST".equals(method)
                && Set.of("/model/chat", "/model/embedding", "/model/rerank").contains(path);
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        CachedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() { return input.read(); }
            };
        }
    }
}
