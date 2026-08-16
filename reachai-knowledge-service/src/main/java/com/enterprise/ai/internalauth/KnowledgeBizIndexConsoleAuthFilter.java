package com.enterprise.ai.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.common.internalauth.InternalServiceSecretRing;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Fail-closed Control → Knowledge gate for platform-console business-index operations. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
public class KnowledgeBizIndexConsoleAuthFilter extends OncePerRequestFilter {

    public static final String PREFIX = "/internal/knowledge/console/biz-index";
    public static final String VERIFIED_TENANT_ATTRIBUTE =
            "reachai.knowledge.console.verified-tenant";
    public static final String VERIFIED_ACTOR_ATTRIBUTE =
            "reachai.knowledge.console.verified-actor";

    private final List<String> verificationSecrets;
    private final long skewMillis;
    private final long maxBodyBytes;
    private final KnowledgeInternalNonceStore nonceStore;

    @Autowired
    public KnowledgeBizIndexConsoleAuthFilter(
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}") String secret,
            @Value("${reachai.internal.accepted-service-secrets:${REACHAI_INTERNAL_SERVICE_ACCEPTED_SECRETS:}}")
            String acceptedServiceSecrets,
            @Value("${reachai.internal.auth.skew-seconds:300}") long skewSeconds,
            @Value("${reachai.knowledge.console-ingress.max-body-bytes:104857600}") long maxBodyBytes,
            KnowledgeInternalNonceStore nonceStore,
            Environment environment) {
        this.verificationSecrets = InternalServiceSecretRing.accepted(secret, acceptedServiceSecrets);
        if (isProduction(environment)) {
            InternalServiceSecretRing.requireStrong(secret, verificationSecrets);
        }
        this.skewMillis = Math.max(1, skewSeconds) * 1000L;
        this.maxBodyBytes = Math.max(0, maxBodyBytes);
        this.nonceStore = nonceStore;
    }

    KnowledgeBizIndexConsoleAuthFilter(String secret,
                                       String acceptedServiceSecrets,
                                       long skewSeconds,
                                       long maxBodyBytes,
                                       KnowledgeInternalNonceStore nonceStore) {
        this(secret, acceptedServiceSecrets, skewSeconds, maxBodyBytes, nonceStore, null);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = normalizedPath(request);
        return !(PREFIX.equals(path) || path.startsWith(PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try (KnowledgeReplayableBodyRequest cached = KnowledgeReplayableBodyRequest.capture(
                request, maxBodyBytes, KnowledgeReplayableBodyRequest.DEFAULT_MEMORY_THRESHOLD_BYTES)) {
            VerifiedConsoleIdentity identity = verify(cached, cached.bodySha256());
            if (identity == null) {
                unauthorized(response);
                return;
            }
            cached.setAttribute(VERIFIED_TENANT_ATTRIBUTE, identity.tenantId());
            cached.setAttribute(VERIFIED_ACTOR_ATTRIBUTE, identity.actorId());
            filterChain.doFilter(cached, response);
        } catch (KnowledgeReplayableBodyRequest.BodyTooLargeException tooLarge) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            writeJson(response,
                    "{\"code\":\"KNOWLEDGE_CONSOLE_REQUEST_TOO_LARGE\",\"message\":\"signed request body exceeds the configured limit\"}");
        }
    }

    private VerifiedConsoleIdentity verify(HttpServletRequest request, String actualDigest) {
        if (verificationSecrets.isEmpty()) {
            return null;
        }
        String caller = normalized(request.getHeader(InternalServiceAuthHeaders.CALLER));
        String source = normalized(request.getHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE))
                .toUpperCase(Locale.ROOT);
        String tenant = normalized(request.getHeader(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
        String actor = normalized(request.getHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        String timestamp = normalized(request.getHeader(InternalServiceAuthHeaders.TIMESTAMP));
        String nonce = normalized(request.getHeader(InternalServiceAuthHeaders.NONCE));
        String suppliedDigest = normalized(request.getHeader(InternalServiceAuthHeaders.BODY_SHA256));
        String signature = normalized(request.getHeader(InternalServiceAuthHeaders.SIGNATURE));
        if (!InternalServiceAuthHeaders.CALLER_CONTROL.equals(caller)
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(source)
                || !StringUtils.hasText(tenant) || !StringUtils.hasText(actor)
                || !StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce)
                || suppliedDigest.length() != 64 || signature.length() != 64
                || tenant.length() > 96 || actor.length() > 128 || nonce.length() > 128
                || timestamp.length() > 20) {
            return null;
        }
        long requestMillis;
        try {
            requestMillis = Long.parseLong(timestamp);
        } catch (NumberFormatException invalid) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - requestMillis) > skewMillis
                || !InternalServiceHmac.digestEqualsConstantTime(actualDigest, suppliedDigest)) {
            return null;
        }
        String canonical = InternalServiceHmac.canonical(
                request.getMethod(),
                normalizedPath(request),
                caller,
                source,
                tenant,
                actor,
                timestamp,
                nonce,
                actualDigest);
        if (!InternalServiceHmac.verifyAnyConstantTime(verificationSecrets, canonical, signature)
                || !nonceStore.tryConsume(caller, nonce, Duration.ofMillis(2 * skewMillis))) {
            return null;
        }
        return new VerifiedConsoleIdentity(tenant, actor);
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        writeJson(response,
                "{\"code\":\"KNOWLEDGE_CONSOLE_INTERNAL_AUTH_REQUIRED\",\"message\":\"internal service authentication failed\"}");
    }

    private static void writeJson(HttpServletResponse response, String body) throws IOException {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(body);
    }

    private static String normalizedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (StringUtils.hasText(context) && path != null && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isProduction(Environment environment) {
        return environment != null && Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .anyMatch(profile -> "prod".equals(profile) || "production".equals(profile));
    }

    private record VerifiedConsoleIdentity(String tenantId, String actorId) {
    }
}
