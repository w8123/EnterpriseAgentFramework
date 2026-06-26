package com.enterprise.ai.agent.platform.control.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmbedSessionService {

    private final EmbedSessionMapper mapper;
    private final ObjectMapper objectMapper;

    public EmbedSessionEntity create(EmbedTokenClaims claims, String pageKey, String pageInstanceId, String route, List<String> bridgeActions, String sdkVersion) {
        if (claims == null) {
            throw new EmbedTokenException("embed token claims are required");
        }
        if (StringUtils.hasText(pageKey)
                && StringUtils.hasText(claims.getPageKey())
                && !pageKey.equals(claims.getPageKey())) {
            throw new EmbedTokenException("pageKey does not match embed token");
        }
        if (!StringUtils.hasText(pageInstanceId) || !pageInstanceId.equals(claims.getPageInstanceId())) {
            throw new EmbedTokenException("pageInstanceId does not match embed token");
        }
        if (StringUtils.hasText(route)
                && StringUtils.hasText(claims.getRoute())
                && !route.equals(claims.getRoute())) {
            throw new EmbedTokenException("route does not match embed token");
        }
        EmbedSessionEntity entity = new EmbedSessionEntity();
        entity.setSessionId("embed-" + UUID.randomUUID().toString().replace("-", ""));
        entity.setTenantId(claims.getTenantId());
        entity.setAppId(claims.getAppId());
        entity.setProjectCode(claims.getProjectCode());
        entity.setAgentId(claims.getAgentId());
        entity.setExternalUserId(claims.getExternalUserId());
        entity.setGlobalUserId(claims.getGlobalUserId());
        entity.setPageKey(StringUtils.hasText(pageKey) ? pageKey : claims.getPageKey());
        entity.setPageInstanceId(pageInstanceId);
        entity.setRoute(StringUtils.hasText(route) ? route : claims.getRoute());
        entity.setOrigin(claims.getOrigin());
        entity.setSdkVersion(StringUtils.hasText(sdkVersion) ? sdkVersion.trim() : null);
        entity.setBridgeActionsJson(writeJson(bridgeActions == null ? List.of() : bridgeActions));
        entity.setStatus("ACTIVE");
        entity.setExpiresAt(LocalDateTime.ofInstant(Instant.ofEpochSecond(claims.getExpiresAt()), ZoneId.systemDefault()));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.insert(entity);
        return entity;
    }

    public EmbedSessionEntity requireActiveSession(String sessionId, EmbedTokenClaims claims) {
        if (claims == null) {
            throw new EmbedTokenException("embed token claims are required");
        }
        EmbedSessionEntity entity = mapper.selectOne(Wrappers.<EmbedSessionEntity>lambdaQuery()
                .eq(EmbedSessionEntity::getSessionId, sessionId)
                .eq(EmbedSessionEntity::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (entity == null) {
            throw new EmbedTokenException("embed chat session not found");
        }
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new EmbedTokenException("embed chat session is expired");
        }
        assertSessionClaimMatch("tenantId", entity.getTenantId(), claims.getTenantId());
        assertSessionClaimMatch("appId", entity.getAppId(), claims.getAppId());
        assertSessionClaimMatch("projectCode", entity.getProjectCode(), claims.getProjectCode());
        assertSessionClaimMatch("agentId", entity.getAgentId(), claims.getAgentId());
        assertSessionClaimMatch("externalUserId", entity.getExternalUserId(), claims.getExternalUserId());
        assertSessionClaimMatch("globalUserId", entity.getGlobalUserId(), claims.getGlobalUserId());
        assertSessionClaimMatch("pageInstanceId", entity.getPageInstanceId(), claims.getPageInstanceId());
        assertSessionClaimMatch("pageKey", entity.getPageKey(), claims.getPageKey());
        return entity;
    }

    private void assertSessionClaimMatch(String field, String sessionValue, String claimValue) {
        if (!StringUtils.hasText(sessionValue) && !StringUtils.hasText(claimValue)) {
            return;
        }
        if (!StringUtils.hasText(sessionValue) || !StringUtils.hasText(claimValue)) {
            throw new EmbedTokenException("embed chat session does not match token: " + field);
        }
        if (!sessionValue.equals(claimValue)) {
            throw new EmbedTokenException("embed chat session does not match token: " + field);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }
}
