package com.enterprise.ai.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.common.internalauth.InternalServiceSecretRing;
import jakarta.servlet.FilterChain;
import jakarta.servlet.MultipartConfigElement;
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

/** Fail-closed HMAC gate for Capability-verified project business-index ingress. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 18)
public class KnowledgeProjectIngressAuthFilter extends OncePerRequestFilter {

    public static final String PREFIX = "/internal/knowledge/project-ingress/projects";
    public static final String VERIFIED_PROJECT_ATTRIBUTE =
            "reachai.knowledge.project-ingress.verified-project";
    public static final String VERIFIED_CREDENTIAL_ATTRIBUTE =
            "reachai.knowledge.project-ingress.verified-credential";

    private final List<String> verificationSecrets;
    private final long skewMillis;
    private final long maxBodyBytes;
    private final KnowledgeInternalNonceStore nonceStore;
    private MultipartConfigElement multipartConfig;

    @Autowired(required = false)
    void configureMultipart(MultipartConfigElement config) {
        this.multipartConfig = config;
    }

    @Autowired
    public KnowledgeProjectIngressAuthFilter(
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}") String secret,
            @Value("${reachai.internal.accepted-service-secrets:${REACHAI_INTERNAL_SERVICE_ACCEPTED_SECRETS:}}")
            String acceptedServiceSecrets,
            @Value("${reachai.internal.auth.skew-seconds:300}") long skewSeconds,
            @Value("${reachai.knowledge.project-ingress.max-body-bytes:10485760}") long maxBodyBytes,
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

    KnowledgeProjectIngressAuthFilter(String secret,
                                      String acceptedServiceSecrets,
                                      long skewSeconds,
                                      long maxBodyBytes,
                                      KnowledgeInternalNonceStore nonceStore) {
        this(secret, acceptedServiceSecrets, skewSeconds, maxBodyBytes, nonceStore, null);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = normalizedPath(request);
        return !(path.startsWith(PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try (KnowledgeReplayableBodyRequest cached = KnowledgeReplayableBodyRequest.capture(
                request, maxBodyBytes, KnowledgeReplayableBodyRequest.DEFAULT_MEMORY_THRESHOLD_BYTES)) {
            VerifiedProjectIdentity identity = verify(cached, cached.bodySha256());
            if (identity == null) {
                reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "KNOWLEDGE_PROJECT_INGRESS_INTERNAL_AUTH_REQUIRED",
                        "internal service authentication failed");
                return;
            }
            cached.setAttribute(VERIFIED_PROJECT_ATTRIBUTE, identity.projectCode());
            cached.setAttribute(VERIFIED_CREDENTIAL_ATTRIBUTE, identity.credentialActorId());
            cached.configureMultipart(multipartConfig);
            filterChain.doFilter(cached, response);
        } catch (KnowledgeReplayableBodyRequest.BodyTooLargeException tooLarge) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "KNOWLEDGE_PROJECT_INGRESS_REQUEST_TOO_LARGE",
                    "signed request body exceeds the configured limit");
        }
    }

    private VerifiedProjectIdentity verify(HttpServletRequest request, String actualDigest) {
        String caller = normalized(request.getHeader(InternalServiceAuthHeaders.CALLER));
        String source = normalized(request.getHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE))
                .toUpperCase(Locale.ROOT);
        String project = normalized(request.getHeader(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
        String credentialActor = normalized(request.getHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        String timestamp = normalized(request.getHeader(InternalServiceAuthHeaders.TIMESTAMP));
        String nonce = normalized(request.getHeader(InternalServiceAuthHeaders.NONCE));
        String digest = normalized(request.getHeader(InternalServiceAuthHeaders.BODY_SHA256));
        String signature = normalized(request.getHeader(InternalServiceAuthHeaders.SIGNATURE));
        String path = normalizedPath(request);
        if (verificationSecrets.isEmpty()
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(caller)
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL.equals(source)
                || !project.matches("[a-z0-9][a-z0-9_-]{0,95}")
                || !credentialActor.matches("credential:[1-9][0-9]{0,18}")
                || !path.startsWith(PREFIX + "/" + project + "/biz-index/")
                || timestamp.length() > 20 || nonce.length() > 128
                || digest.length() != 64 || signature.length() != 64) {
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
                || !InternalServiceHmac.digestEqualsConstantTime(actualDigest, digest)) {
            return null;
        }
        String canonical = InternalServiceHmac.canonical(
                request.getMethod(), path, caller, source, project, credentialActor,
                timestamp, nonce, actualDigest);
        if (!InternalServiceHmac.verifyAnyConstantTime(verificationSecrets, canonical, signature)
                || !nonceStore.tryConsume(caller, nonce, Duration.ofMillis(2 * skewMillis))) {
            return null;
        }
        return new VerifiedProjectIdentity(project, credentialActor);
    }

    private static void reject(HttpServletResponse response,
                               int status,
                               String code,
                               String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
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
                ? path.substring(0, path.length() - 1) : path;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isProduction(Environment environment) {
        return environment != null && Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .anyMatch(profile -> "prod".equals(profile) || "production".equals(profile));
    }

    private record VerifiedProjectIdentity(String projectCode, String credentialActorId) {
    }
}
