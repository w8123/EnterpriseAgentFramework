package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RegistrySecurityService {

    private static final long MAX_CLOCK_SKEW_SECONDS = 300;

    private final RegistryCredentialMapper credentialMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public RegistryCredentialEntity savePrimaryCredential(Long projectId,
                                                          String projectCode,
                                                          String appKey,
                                                          String appSecret) {
        if (projectId == null || !StringUtils.hasText(projectCode)) {
            throw new IllegalArgumentException("registry project is required");
        }
        if (!StringUtils.hasText(appKey) || !StringUtils.hasText(appSecret)) {
            throw new IllegalArgumentException("registry credential appKey/appSecret is required");
        }
        RegistryCredentialEntity entity = findPrimaryActiveCredential(projectCode).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (entity == null) {
            entity = new RegistryCredentialEntity();
            entity.setProjectId(projectId);
            entity.setProjectCode(projectCode.trim());
            entity.setCreatedAt(now);
            entity.setAppKey(appKey.trim());
            entity.setAppSecret(appSecret.trim());
            entity.setStatus("ACTIVE");
            entity.setRevision(1L);
            entity.setUpdatedAt(now);
            credentialMapper.insert(entity);
        } else {
            if (java.util.Objects.equals(entity.getProjectId(), projectId)
                    && java.util.Objects.equals(entity.getProjectCode(), projectCode.trim())
                    && java.util.Objects.equals(entity.getAppKey(), appKey.trim())
                    && java.util.Objects.equals(entity.getAppSecret(), appSecret.trim())) return entity;
            int updated = credentialMapper.update(null, Wrappers.<RegistryCredentialEntity>lambdaUpdate()
                    .eq(RegistryCredentialEntity::getId, entity.getId())
                    .eq(RegistryCredentialEntity::getProjectId, projectId)
                    .eq(RegistryCredentialEntity::getProjectCode, projectCode.trim())
                    .eq(RegistryCredentialEntity::getStatus, "ACTIVE")
                    .eq(RegistryCredentialEntity::getRevision, entity.getRevision())
                    // Cover each utf8mb4 column's full byte width; lengths distinguish binary padding.
                    .apply("CAST(app_key AS BINARY(512)) = CAST({0} AS BINARY(512)) "
                            + "AND OCTET_LENGTH(app_key) = OCTET_LENGTH({0})", entity.getAppKey())
                    .apply("CAST(app_secret AS BINARY(1024)) = CAST({0} AS BINARY(1024)) "
                            + "AND OCTET_LENGTH(app_secret) = OCTET_LENGTH({0})", entity.getAppSecret())
                    .set(RegistryCredentialEntity::getAppKey, appKey.trim())
                    .set(RegistryCredentialEntity::getAppSecret, appSecret.trim())
                    .setSql("revision = revision + 1")
                    .set(RegistryCredentialEntity::getUpdatedAt, now));
            if (updated != 1) {
                throw new IllegalArgumentException("registry credential changed during rotation; reload and retry");
            }
        }
        return requireCredential(entity.getId());
    }

    /** Full administrative policy replacement; credential identity is owned by rotation. */
    @Transactional
    public RegistryCredentialEntity updateAdministrativePolicy(Long credentialId,
                                                               List<String> allowedOrigins,
                                                               List<String> allowedAgentIds,
                                                               int tokenTtlSeconds,
                                                               String status) {
        var update = Wrappers.<RegistryCredentialEntity>lambdaUpdate()
                .eq(RegistryCredentialEntity::getId, credentialId)
                .set(RegistryCredentialEntity::getAllowedOriginsJson, writeJson(allowedOrigins))
                .set(RegistryCredentialEntity::getAllowedAgentIdsJson, writeJson(allowedAgentIds))
                .set(RegistryCredentialEntity::getTokenTtlSeconds, tokenTtlSeconds)
                .setSql("revision = revision + 1")
                .set(RegistryCredentialEntity::getUpdatedAt, LocalDateTime.now());
        if (StringUtils.hasText(status)) update.set(RegistryCredentialEntity::getStatus, status.trim());
        if (credentialMapper.update(null, update) != 1) {
            throw new IllegalArgumentException("Credential not found: " + credentialId);
        }
        return requireCredential(credentialId);
    }

    private RegistryCredentialEntity requireCredential(Long credentialId) {
        RegistryCredentialEntity entity = credentialMapper.selectById(credentialId);
        if (entity == null) throw new IllegalArgumentException("Credential not found: " + credentialId);
        return entity;
    }

    public void updateEmbedPolicy(String projectCode,
                                  String appKey,
                                  List<String> allowedOrigins,
                                  List<String> allowedAgentIds,
                                  Integer tokenTtlSeconds) {
        if (!StringUtils.hasText(projectCode) || !StringUtils.hasText(appKey)) {
            return;
        }
        RegistryCredentialEntity current = findActiveCredential(projectCode, appKey);
        if (current == null) return;
        var update = Wrappers.<RegistryCredentialEntity>lambdaUpdate()
                .eq(RegistryCredentialEntity::getProjectCode, projectCode)
                .eq(RegistryCredentialEntity::getAppKey, appKey)
                .eq(RegistryCredentialEntity::getRevision, current.getRevision())
                .eq(RegistryCredentialEntity::getStatus, "ACTIVE");
        boolean changed = false;
        if (allowedOrigins != null && !allowedOrigins.isEmpty()
                && !java.util.Objects.equals(writeJson(allowedOrigins), current.getAllowedOriginsJson())) {
            update.set(RegistryCredentialEntity::getAllowedOriginsJson, writeJson(allowedOrigins));
            changed = true;
        }
        if (allowedAgentIds != null && !allowedAgentIds.isEmpty()
                && !java.util.Objects.equals(writeJson(allowedAgentIds), current.getAllowedAgentIdsJson())) {
            update.set(RegistryCredentialEntity::getAllowedAgentIdsJson, writeJson(allowedAgentIds));
            changed = true;
        }
        if (tokenTtlSeconds != null && tokenTtlSeconds > 0
                && !java.util.Objects.equals(tokenTtlSeconds, current.getTokenTtlSeconds())) {
            update.set(RegistryCredentialEntity::getTokenTtlSeconds, tokenTtlSeconds);
            changed = true;
        }
        if (changed) {
            update.set(RegistryCredentialEntity::getUpdatedAt, LocalDateTime.now());
            update.setSql("revision = revision + 1");
            if (credentialMapper.update(null, update) != 1) throw new IllegalArgumentException("registry credential policy changed concurrently");
        }
    }

    /**
     * 管理端展示接入配置用：取该项目下一条 ACTIVE 凭证（多凭证时取最近更新的）。
     */
    public Optional<RegistryCredentialEntity> findPrimaryActiveCredential(String projectCode) {
        if (!StringUtils.hasText(projectCode)) {
            return Optional.empty();
        }
        return Optional.ofNullable(credentialMapper.selectOne(Wrappers.<RegistryCredentialEntity>lambdaQuery()
                .eq(RegistryCredentialEntity::getProjectCode, projectCode.trim())
                .eq(RegistryCredentialEntity::getStatus, "ACTIVE")
                .orderByDesc(RegistryCredentialEntity::getUpdatedAt)
                .orderByDesc(RegistryCredentialEntity::getId)
                .last("LIMIT 1")));
    }

    /**
     * 项目修改 project_code 后，按 project_id 同步注册凭证表中的编码。
     */
    public void syncCredentialProjectCode(Long projectId, String newProjectCode) {
        if (projectId == null || !StringUtils.hasText(newProjectCode)) {
            return;
        }
        credentialMapper.update(null, Wrappers.<RegistryCredentialEntity>lambdaUpdate()
                .eq(RegistryCredentialEntity::getProjectId, projectId)
                .ne(RegistryCredentialEntity::getProjectCode, newProjectCode.trim())
                .setSql("revision = revision + 1")
                .set(RegistryCredentialEntity::getProjectCode, newProjectCode.trim()));
    }

    public RegistryCredentialEntity verifyRequired(String projectCode, RegistrySignatureHeaders headers) {
        RegistryCredentialEntity credential = findActiveCredential(projectCode, headers == null ? null : headers.appKey());
        if (credential == null) {
            throw new IllegalArgumentException("registry project credential is invalid");
        }
        if (headers == null
                || !StringUtils.hasText(headers.timestamp())
                || !StringUtils.hasText(headers.nonce())
                || !StringUtils.hasText(headers.signature())) {
            throw new IllegalArgumentException("registry request signature headers are required");
        }
        if (credential.getExpiresAt() != null && credential.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("registry project credential is expired");
        }
        validateTimestamp(headers.timestamp());
        String message = projectCode + "\n" + headers.timestamp() + "\n" + headers.nonce();
        String expected = hmacSha256Hex(credential.getAppSecret(), message);
        if (!expected.equalsIgnoreCase(headers.signature())) {
            throw new IllegalArgumentException("registry request signature is invalid");
        }
        return credential;
    }

    private RegistryCredentialEntity findActiveCredential(String projectCode, String appKey) {
        if (!StringUtils.hasText(projectCode) || !StringUtils.hasText(appKey)) {
            return null;
        }
        return credentialMapper.selectOne(Wrappers.<RegistryCredentialEntity>lambdaQuery()
                .eq(RegistryCredentialEntity::getProjectCode, projectCode)
                .eq(RegistryCredentialEntity::getAppKey, appKey)
                .eq(RegistryCredentialEntity::getStatus, "ACTIVE")
                .last("limit 1"));
    }

    private void validateTimestamp(String raw) {
        try {
            long epochMillis = Long.parseLong(raw);
            long skew = Math.abs(ChronoUnit.SECONDS.between(Instant.ofEpochMilli(epochMillis), Instant.now()));
            if (skew > MAX_CLOCK_SKEW_SECONDS) {
                throw new IllegalArgumentException("注册中心请求时间戳超出允许范围");
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("注册中心请求时间戳无效");
        }
    }

    private String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("签名计算失败", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("embed policy json serialize failed", ex);
        }
    }

    public record RegistrySignatureHeaders(String appKey, String timestamp, String nonce, String signature) {
    }
}
