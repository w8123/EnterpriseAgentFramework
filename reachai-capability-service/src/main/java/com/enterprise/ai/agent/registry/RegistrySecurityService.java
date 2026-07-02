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

    public void upsertCredential(Long projectId, String projectCode, String appKey, String appSecret) {
        if (!StringUtils.hasText(appKey) || !StringUtils.hasText(appSecret)) {
            return;
        }
        RegistryCredentialEntity entity = credentialMapper.selectOne(Wrappers.<RegistryCredentialEntity>lambdaQuery()
                .eq(RegistryCredentialEntity::getProjectCode, projectCode)
                .eq(RegistryCredentialEntity::getAppKey, appKey)
                .last("limit 1"));
        if (entity == null) {
            entity = new RegistryCredentialEntity();
            entity.setProjectId(projectId);
            entity.setProjectCode(projectCode);
            entity.setAppKey(appKey);
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.setAppSecret(appSecret);
        entity.setStatus("ACTIVE");
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getId() == null) {
            credentialMapper.insert(entity);
        } else {
            credentialMapper.updateById(entity);
        }
    }

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
        RegistryCredentialEntity entity = credentialMapper.selectOne(Wrappers.<RegistryCredentialEntity>lambdaQuery()
                .eq(RegistryCredentialEntity::getProjectCode, projectCode.trim())
                .eq(RegistryCredentialEntity::getStatus, "ACTIVE")
                .orderByDesc(RegistryCredentialEntity::getUpdatedAt)
                .last("limit 1"));
        if (entity == null) {
            entity = new RegistryCredentialEntity();
            entity.setProjectId(projectId);
            entity.setProjectCode(projectCode.trim());
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.setAppKey(appKey.trim());
        entity.setAppSecret(appSecret.trim());
        entity.setStatus("ACTIVE");
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getId() == null) {
            credentialMapper.insert(entity);
        } else {
            credentialMapper.updateById(entity);
        }
        return entity;
    }

    public void updateEmbedPolicy(String projectCode,
                                  String appKey,
                                  List<String> allowedOrigins,
                                  List<String> allowedAgentIds,
                                  Integer tokenTtlSeconds) {
        RegistryCredentialEntity entity = findActiveCredential(projectCode, appKey);
        if (entity == null) {
            return;
        }
        boolean changed = false;
        if (allowedOrigins != null && !allowedOrigins.isEmpty()) {
            entity.setAllowedOriginsJson(writeJson(allowedOrigins));
            changed = true;
        }
        if (allowedAgentIds != null && !allowedAgentIds.isEmpty()) {
            entity.setAllowedAgentIdsJson(writeJson(allowedAgentIds));
            changed = true;
        }
        if (tokenTtlSeconds != null && tokenTtlSeconds > 0) {
            entity.setTokenTtlSeconds(tokenTtlSeconds);
            changed = true;
        }
        if (changed) {
            entity.setUpdatedAt(LocalDateTime.now());
            credentialMapper.updateById(entity);
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
                .set(RegistryCredentialEntity::getProjectCode, newProjectCode.trim()));
    }

    public void verifyIfConfigured(String projectCode, RegistrySignatureHeaders headers) {
        boolean projectRequiresSignature = credentialMapper.selectCount(Wrappers.<RegistryCredentialEntity>lambdaQuery()
                .eq(RegistryCredentialEntity::getProjectCode, projectCode)
                .eq(RegistryCredentialEntity::getStatus, "ACTIVE")) > 0;
        if (!projectRequiresSignature) {
            return;
        }
        RegistryCredentialEntity credential = findActiveCredential(projectCode, headers == null ? null : headers.appKey());
        if (credential == null) {
            throw new IllegalArgumentException("注册中心项目凭证无效");
        }
        if (headers == null
                || !StringUtils.hasText(headers.timestamp())
                || !StringUtils.hasText(headers.nonce())
                || !StringUtils.hasText(headers.signature())) {
            throw new IllegalArgumentException("注册中心请求缺少签名头");
        }
        validateTimestamp(headers.timestamp());
        String message = projectCode + "\n" + headers.timestamp() + "\n" + headers.nonce();
        String expected = hmacSha256Hex(credential.getAppSecret(), message);
        if (!expected.equalsIgnoreCase(headers.signature())) {
            throw new IllegalArgumentException("注册中心请求签名无效");
        }
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
