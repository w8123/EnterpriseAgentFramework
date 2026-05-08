package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class RegistrySecurityService {

    private static final long MAX_CLOCK_SKEW_SECONDS = 300;

    private final RegistryCredentialMapper credentialMapper;

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

    public record RegistrySignatureHeaders(String appKey, String timestamp, String nonce, String signature) {
    }
}
