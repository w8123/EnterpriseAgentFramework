package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Locale;

/** Verifies body-bound public project requests without exposing the credential secret to Control. */
@Service
public class RegistryProjectRequestVerificationService {

    private static final String INGRESS_PREFIX = "/api/knowledge-ingress/projects/";

    private final RegistryCredentialMapper credentialMapper;
    private final RegistryProjectRequestNonceStore nonceStore;
    private final long skewMillis;
    private final long nonceTtlSeconds;
    private final int nonceMaxEntries;

    public RegistryProjectRequestVerificationService(
            RegistryCredentialMapper credentialMapper,
            RegistryProjectRequestNonceStore nonceStore,
            @Value("${reachai.registry.project-request.skew-seconds:300}") long skewSeconds,
            @Value("${reachai.registry.project-request.nonce-ttl-seconds:600}") long nonceTtlSeconds,
            @Value("${reachai.registry.project-request.nonce-max-entries:1000000}") int nonceMaxEntries) {
        this.credentialMapper = credentialMapper;
        this.nonceStore = nonceStore;
        this.skewMillis = Math.max(1, skewSeconds) * 1000L;
        this.nonceTtlSeconds = Math.max(2 * Math.max(1, skewSeconds), nonceTtlSeconds);
        this.nonceMaxEntries = Math.max(1, nonceMaxEntries);
    }

    public VerifiedProjectRequest verify(ProjectRequest request) {
        validateShape(request);
        RegistryCredentialEntity credential = credentialMapper.selectOne(
                Wrappers.<RegistryCredentialEntity>lambdaQuery()
                        .eq(RegistryCredentialEntity::getProjectCode, request.projectCode().trim())
                        .eq(RegistryCredentialEntity::getAppKey, request.appKey().trim())
                        .eq(RegistryCredentialEntity::getStatus, "ACTIVE")
                        .last("LIMIT 1"));
        if (credential == null || !StringUtils.hasText(credential.getAppSecret())
                || (credential.getExpiresAt() != null
                && !credential.getExpiresAt().isAfter(LocalDateTime.now()))) {
            throw invalid();
        }
        long requestMillis;
        try {
            requestMillis = Long.parseLong(request.timestamp().trim());
        } catch (NumberFormatException malformed) {
            throw invalid();
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - requestMillis) > skewMillis) {
            throw invalid();
        }
        String canonical = ReachAiProjectRequestSigner.canonical(
                request.method(),
                request.path(),
                request.projectCode(),
                request.appKey(),
                request.timestamp(),
                request.nonce(),
                request.bodySha256());
        String expected = com.enterprise.ai.reach.sdk.auth.ReachAiSigner.sign(
                credential.getAppSecret(), canonical);
        if (!constantTimeEquals(expected, request.signature())
                || !nonceStore.tryConsume(
                request.projectCode(), request.appKey(), request.nonce(), now,
                nonceTtlSeconds, nonceMaxEntries)) {
            throw invalid();
        }
        return new VerifiedProjectRequest(
                credential.getProjectId(),
                credential.getProjectCode(),
                credential.getId(),
                sha256(credential.getAppKey()));
    }

    private void validateShape(ProjectRequest request) {
        if (request == null || blank(request.projectCode()) || blank(request.appKey())
                || blank(request.method()) || blank(request.path()) || blank(request.timestamp())
                || blank(request.nonce()) || blank(request.bodySha256()) || blank(request.signature())) {
            throw invalid();
        }
        String project = request.projectCode().trim();
        String method = request.method().trim().toUpperCase(Locale.ROOT);
        String path = request.path().trim();
        if (!project.matches("[a-z0-9][a-z0-9_-]{0,95}")
                || !("POST".equals(method) || "DELETE".equals(method))
                || !path.startsWith(INGRESS_PREFIX + project + "/biz-index/")
                || path.contains("?") || path.contains("#") || path.contains("..")
                || request.appKey().length() > 128 || request.nonce().length() > 128
                || request.timestamp().length() > 20
                || !request.bodySha256().matches("(?i)[0-9a-f]{64}")
                || !request.signature().matches("(?i)[0-9a-f]{64}")) {
            throw invalid();
        }
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        return expected != null && supplied != null && MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                supplied.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        return ReachAiProjectRequestSigner.bodySha256Hex(
                String.valueOf(value).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("project request credential is invalid");
    }

    public record ProjectRequest(
            String projectCode,
            String appKey,
            String method,
            String path,
            String timestamp,
            String nonce,
            String bodySha256,
            String signature
    ) {
    }

    public record VerifiedProjectRequest(
            Long projectId,
            String projectCode,
            Long credentialId,
            String appKeyHash
    ) {
    }
}
