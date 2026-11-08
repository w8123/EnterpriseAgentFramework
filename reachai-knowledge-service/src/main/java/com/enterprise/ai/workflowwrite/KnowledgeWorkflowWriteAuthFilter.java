package com.enterprise.ai.workflowwrite;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.common.internalauth.InternalServiceSecretRing;
import com.enterprise.ai.internalauth.KnowledgeInternalNonceStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Fail-closed HMAC gate: only Runtime workflow KNOWLEDGE_WRITE nodes may write text into a knowledge base. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class KnowledgeWorkflowWriteAuthFilter extends OncePerRequestFilter {

    public static final String PREFIX = "/internal/knowledge/workflow-write";
    public static final String SOURCE = "WORKFLOW_KNOWLEDGE_WRITE";
    private static final int MAX_BODY = 1_048_576;

    private final List<String> verificationSecrets;
    private final long skewMillis;
    private final KnowledgeInternalNonceStore nonceStore;

    @Autowired
    public KnowledgeWorkflowWriteAuthFilter(
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}") String secret,
            @Value("${reachai.internal.accepted-service-secrets:${REACHAI_INTERNAL_SERVICE_ACCEPTED_SECRETS:}}")
            String acceptedServiceSecrets,
            @Value("${reachai.internal.auth.skew-seconds:300}") long skewSeconds,
            KnowledgeInternalNonceStore nonceStore) {
        this.verificationSecrets = InternalServiceSecretRing.accepted(secret, acceptedServiceSecrets);
        this.skewMillis = Math.max(1, skewSeconds) * 1000L;
        this.nonceStore = nonceStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !normalizedPath(request).startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY || !verify(request, body)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"KNOWLEDGE_WORKFLOW_WRITE_AUTH_REQUIRED\"}");
            return;
        }
        chain.doFilter(new CachedRequest(request, body), response);
    }

    private boolean verify(HttpServletRequest request, byte[] body) {
        if (verificationSecrets.isEmpty()) return false;
        String caller = request.getHeader(InternalServiceAuthHeaders.CALLER);
        String source = request.getHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE);
        String tenant = request.getHeader(InternalServiceAuthHeaders.IDENTITY_TENANT_ID);
        String userId = request.getHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID);
        String timestamp = request.getHeader(InternalServiceAuthHeaders.TIMESTAMP);
        String nonce = request.getHeader(InternalServiceAuthHeaders.NONCE);
        String digest = request.getHeader(InternalServiceAuthHeaders.BODY_SHA256);
        String signature = request.getHeader(InternalServiceAuthHeaders.SIGNATURE);
        if (!InternalServiceAuthHeaders.CALLER_RUNTIME.equals(caller) || !SOURCE.equals(source)
                || !StringUtils.hasText(tenant) || userId == null
                || !StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce)
                || !StringUtils.hasText(digest) || !StringUtils.hasText(signature)) return false;
        if (tenant.length() > 96 || userId.length() > 256 || nonce.length() > 128
                || timestamp.length() > 20 || digest.length() != 64 || signature.length() != 64) return false;
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            return false;
        }
        if (Math.abs(System.currentTimeMillis() - ts) > skewMillis) return false;
        String actual = InternalServiceHmac.bodySha256Hex(body);
        if (!InternalServiceHmac.digestEqualsConstantTime(actual, digest)) return false;
        String canonical = InternalServiceHmac.canonical(request.getMethod(), normalizedPath(request), caller,
                source, tenant, userId, timestamp, nonce, actual);
        if (!InternalServiceHmac.verifyAnyConstantTime(verificationSecrets, canonical, signature)) return false;
        return nonceStore.tryConsume(caller, nonce, Duration.ofMillis(2 * skewMillis));
    }

    private static String normalizedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (StringUtils.hasText(context) && path.startsWith(context)) path = path.substring(context.length());
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return source.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() { return source.read(); }
            };
        }
    }
}
