package com.enterprise.ai.capability.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistryCredentialMapper;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CapabilityEmbedCredentialPolicyInternalService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final RegistryCredentialMapper credentialMapper;
    private final ObjectMapper objectMapper;
    private final RegistrySecurityService registrySecurityService;

    public List<EmbedCredentialPolicyView> listPolicies(String projectCode, String status, int limit) {
        LambdaQueryWrapper<RegistryCredentialEntity> wrapper = new LambdaQueryWrapper<RegistryCredentialEntity>()
                .eq(StringUtils.hasText(projectCode), RegistryCredentialEntity::getProjectCode, trim(projectCode))
                .eq(StringUtils.hasText(status), RegistryCredentialEntity::getStatus, trim(status))
                .orderByDesc(RegistryCredentialEntity::getUpdatedAt)
                .orderByDesc(RegistryCredentialEntity::getId)
                .last("limit " + safeLimit(limit, 200));
        return credentialMapper.selectList(wrapper).stream()
                .map(this::toView)
                .toList();
    }

    public EmbedCredentialPolicyView updatePolicy(Long id, EmbedCredentialPolicyUpdate request) {
        RegistryCredentialEntity entity = credentialMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Credential not found: " + id);
        }
        EmbedCredentialPolicyUpdate safeRequest = request == null
                ? new EmbedCredentialPolicyUpdate(List.of(), List.of(), 600, null)
                : request;
        entity.setAllowedOriginsJson(toJson(safeRequest.allowedOrigins() == null ? List.of() : safeRequest.allowedOrigins()));
        entity.setAllowedAgentIdsJson(toJson(safeRequest.allowedAgentIds() == null ? List.of() : safeRequest.allowedAgentIds()));
        entity.setTokenTtlSeconds(safeTtl(safeRequest.tokenTtlSeconds()));
        if (StringUtils.hasText(safeRequest.status())) {
            entity.setStatus(safeRequest.status().trim());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        credentialMapper.updateById(entity);
        return toView(entity);
    }

    public EmbedTokenExchangeVerificationView verifyTokenExchange(String appKey,
                                                                  String timestamp,
                                                                  String nonce,
                                                                  String signature,
                                                                  EmbedTokenExchangeVerificationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("embed token exchange request is required");
        }
        requireText(request.projectCode(), "projectCode");
        requireText(request.agentId(), "agentId");
        requireText(request.origin(), "origin");
        RegistryCredentialEntity credential = registrySecurityService.verifyRequired(
                request.projectCode(),
                new RegistrySecurityService.RegistrySignatureHeaders(appKey, timestamp, nonce, signature));
        ensureOriginAllowed(credential, request.origin());
        ensureAgentAllowed(credential, request.agentId());
        return new EmbedTokenExchangeVerificationView(
                credential.getProjectCode(),
                credential.getAppKey(),
                safeTtl(credential.getTokenTtlSeconds()));
    }

    private int safeLimit(int requested, int fallback) {
        int value = requested <= 0 ? fallback : requested;
        return Math.min(Math.max(value, 1), 1000);
    }

    private int safeTtl(Integer value) {
        if (value == null || value <= 0) {
            return 600;
        }
        return Math.min(value, 86_400);
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("embed credential policy json is invalid", ex);
        }
    }

    private void ensureOriginAllowed(RegistryCredentialEntity credential, String origin) {
        List<String> allowed = readStringList(credential.getAllowedOriginsJson());
        if (allowed.isEmpty()) {
            if (localDevelopmentOrigin(origin)) {
                return;
            }
            throw new IllegalArgumentException("embed origin policy is empty for project: " + credential.getProjectCode()
                    + "; only localhost origins are allowed by default");
        }
        if (allowed.stream().noneMatch(pattern -> originAllowed(pattern, origin))) {
            throw new IllegalArgumentException("embed origin is not allowed: " + origin);
        }
    }

    private void ensureAgentAllowed(RegistryCredentialEntity credential, String agentId) {
        List<String> allowed = readStringList(credential.getAllowedAgentIdsJson());
        if (!allowed.isEmpty() && allowed.stream().noneMatch(agentId::equals)) {
            throw new IllegalArgumentException("agent is not allowed by embed policy: " + agentId);
        }
    }

    private boolean localDevelopmentOrigin(String origin) {
        if (!StringUtils.hasText(origin)) {
            return false;
        }
        try {
            URI uri = URI.create(origin.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && ("localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean originAllowed(String pattern, String origin) {
        if (!StringUtils.hasText(pattern) || !StringUtils.hasText(origin) || "*".equals(pattern.trim())) {
            return false;
        }
        String normalizedPattern = pattern.trim();
        if (!normalizedPattern.contains("*")) {
            return normalizedPattern.equals(origin);
        }
        if (!normalizedPattern.startsWith("https://*.") && !normalizedPattern.startsWith("http://*.")) {
            return false;
        }
        String suffix = normalizedPattern.substring(normalizedPattern.indexOf("*.") + 1);
        int schemeEnd = normalizedPattern.indexOf("://");
        String scheme = normalizedPattern.substring(0, schemeEnd + 3);
        if (!origin.startsWith(scheme) || !origin.endsWith(suffix)) {
            return false;
        }
        String subdomain = origin.substring(scheme.length(), origin.length() - suffix.length());
        return StringUtils.hasText(subdomain) && !subdomain.contains("/") && !subdomain.contains(":");
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("embed policy json is invalid");
        }
    }

    private void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private EmbedCredentialPolicyView toView(RegistryCredentialEntity entity) {
        return new EmbedCredentialPolicyView(
                entity.getId(),
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getAppKey(),
                entity.getAllowedOriginsJson(),
                entity.getAllowedAgentIdsJson(),
                entity.getTokenTtlSeconds(),
                entity.getStatus());
    }

    public record EmbedCredentialPolicyView(
            Long id,
            Long projectId,
            String projectCode,
            String appKey,
            String allowedOriginsJson,
            String allowedAgentIdsJson,
            Integer tokenTtlSeconds,
            String status
    ) {
    }

    public record EmbedCredentialPolicyUpdate(
            List<String> allowedOrigins,
            List<String> allowedAgentIds,
            Integer tokenTtlSeconds,
            String status
    ) {
    }

    public record EmbedTokenExchangeVerificationRequest(
            String projectCode,
            String agentId,
            String origin
    ) {
    }

    public record EmbedTokenExchangeVerificationView(
            String projectCode,
            String appKey,
            Integer tokenTtlSeconds
    ) {
    }
}
