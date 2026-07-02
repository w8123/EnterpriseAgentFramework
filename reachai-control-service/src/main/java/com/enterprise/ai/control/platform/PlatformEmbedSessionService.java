package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlatformEmbedSessionService {

    private final PlatformEmbedSessionMapper mapper;
    private final ObjectMapper objectMapper;

    public PlatformEmbedSessionEntity create(PlatformEmbedTokenClaims claims,
                                             String pageKey,
                                             String pageInstanceId,
                                             String route,
                                             List<String> bridgeActions,
                                             String sdkVersion) {
        if (claims == null) {
            throw new PlatformEmbedTokenException("embed token claims are required");
        }
        if (StringUtils.hasText(pageKey)
                && StringUtils.hasText(claims.getPageKey())
                && !pageKey.equals(claims.getPageKey())) {
            throw new PlatformEmbedTokenException("pageKey does not match embed token");
        }
        if (!StringUtils.hasText(pageInstanceId) || !pageInstanceId.equals(claims.getPageInstanceId())) {
            throw new PlatformEmbedTokenException("pageInstanceId does not match embed token");
        }
        if (StringUtils.hasText(route)
                && StringUtils.hasText(claims.getRoute())
                && !route.equals(claims.getRoute())) {
            throw new PlatformEmbedTokenException("route does not match embed token");
        }

        LocalDateTime now = LocalDateTime.now();
        PlatformEmbedSessionEntity entity = new PlatformEmbedSessionEntity();
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
        entity.setExpiresAt(LocalDateTime.ofInstant(
                Instant.ofEpochSecond(claims.getExpiresAt()), ZoneId.systemDefault()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return entity;
    }

    public PlatformEmbedSessionEntity requireActiveSession(String sessionId, PlatformEmbedTokenClaims claims) {
        if (!StringUtils.hasText(sessionId)) {
            throw new PlatformEmbedTokenException("embed chat session id is required");
        }
        if (claims == null) {
            throw new PlatformEmbedTokenException("embed token claims are required");
        }
        PlatformEmbedSessionEntity entity = mapper.selectOne(Wrappers.<PlatformEmbedSessionEntity>lambdaQuery()
                .eq(PlatformEmbedSessionEntity::getSessionId, sessionId)
                .last("limit 1"));
        if (entity == null || !"ACTIVE".equals(entity.getStatus())) {
            throw new PlatformEmbedTokenException("embed chat session not found: " + sessionId);
        }
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new PlatformEmbedTokenException("embed chat session is expired");
        }
        if (!Objects.equals(entity.getAgentId(), claims.getAgentId())
                || !Objects.equals(entity.getProjectCode(), claims.getProjectCode())
                || !Objects.equals(entity.getExternalUserId(), claims.getExternalUserId())
                || !Objects.equals(entity.getPageInstanceId(), claims.getPageInstanceId())) {
            throw new PlatformEmbedTokenException("embed chat session does not match embed token");
        }
        return entity;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }
}
