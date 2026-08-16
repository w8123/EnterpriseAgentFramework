package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;

/**
 * Issues and consumes enrollment proofs for zero-touch SDK registration.
 * A token is bound to one normalized project code, expires within 72 hours,
 * and is atomically consumed before a persistent registry credential is issued.
 */
@Service
@RequiredArgsConstructor
public class RegistryEnrollmentService {

    public static final int MAX_TTL_HOURS = 72;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RegistryEnrollmentTokenMapper enrollmentTokenMapper;
    private final ScanProjectMapper scanProjectMapper;

    @Transactional
    public IssuedEnrollment create(String requestedProjectCode, Long createdByUserId) {
        String projectCode = normalizeProjectCode(requestedProjectCode);
        if (scanProjectMapper.selectCount(Wrappers.<ScanProjectEntity>lambdaQuery()
                .eq(ScanProjectEntity::getProjectCode, projectCode)) > 0) {
            throw new IllegalArgumentException("a registered project already exists for projectCode: " + projectCode);
        }
        String rawToken = "ren_" + randomUrlToken(32);
        RegistryEnrollmentTokenEntity entity = new RegistryEnrollmentTokenEntity();
        entity.setProjectCode(projectCode);
        entity.setTokenDigest(sha256(rawToken));
        entity.setCreatedByUserId(createdByUserId);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setExpiresAt(entity.getCreatedAt().plusHours(MAX_TTL_HOURS));
        enrollmentTokenMapper.insert(entity);
        return new IssuedEnrollment(rawToken, projectCode, entity.getExpiresAt());
    }

    /**
     * Validates then consumes a token in the caller transaction. The database
     * row lock prevents concurrent use from issuing two credentials.
     */
    @Transactional
    public void consume(String rawToken, String requestedProjectCode) {
        if (!StringUtils.hasText(rawToken)) {
            throw new IllegalArgumentException("one-time registry enrollment token is required for first registration");
        }
        String projectCode = normalizeProjectCode(requestedProjectCode);
        RegistryEnrollmentTokenEntity entity = enrollmentTokenMapper.selectByDigestForUpdate(sha256(rawToken.trim()));
        if (entity == null) {
            throw new IllegalArgumentException("registry enrollment token is invalid");
        }
        if (entity.getConsumedAt() != null) {
            throw new IllegalArgumentException("registry enrollment token has already been used");
        }
        if (entity.getExpiresAt() == null || !entity.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("registry enrollment token has expired");
        }
        if (!projectCode.equals(entity.getProjectCode())) {
            throw new IllegalArgumentException("registry enrollment token is not valid for this projectCode");
        }
        entity.setConsumedAt(LocalDateTime.now());
        enrollmentTokenMapper.updateById(entity);
    }

    public RegistryCredential issueCredential(Long projectId, String projectCode) {
        if (!StringUtils.hasText(projectCode)) {
            throw new IllegalArgumentException("projectCode is required before issuing a registry credential");
        }
        return new RegistryCredential(
                "rak_" + randomUrlToken(18),
                "ras_" + randomUrlToken(36));
    }

    public static String normalizeProjectCode(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("projectCode is required");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("projectCode is invalid");
        }
        return normalized;
    }

    private static String randomUrlToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                out.append(String.format("%02x", item & 0xff));
            }
            return out.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("registry enrollment token digest failed", ex);
        }
    }

    public record IssuedEnrollment(String enrollmentToken, String projectCode, LocalDateTime expiresAt) {
    }

    public record RegistryCredential(String appKey, String appSecret) {
    }
}
