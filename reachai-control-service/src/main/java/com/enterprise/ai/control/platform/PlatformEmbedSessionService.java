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
        requireSamePrincipal(entity, claims);
        if (!Objects.equals(entity.getPageInstanceId(), claims.getPageInstanceId())) {
            throw new PlatformEmbedTokenException("embed chat session does not match embed token");
        }
        refreshExpiry(entity, claims);
        return entity;
    }

    /**
     * A cross-route Page Bridge rebinding deliberately uses a token bound to a
     * new page instance. It still requires the same immutable project, Agent
     * and business user, then a controller verifies the pending NAVIGATE event
     * before changing page identity.
     */
    public PlatformEmbedSessionEntity requireActiveSessionForNavigationRebind(
            String sessionId,
            PlatformEmbedTokenClaims claims) {
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
        requireSamePrincipal(entity, claims);
        refreshExpiry(entity, claims);
        return entity;
    }

    public void rebindBridgeAfterNavigation(PlatformEmbedSessionEntity session,
                                            PlatformEmbedTokenClaims claims,
                                            String pageKey,
                                            String pageInstanceId,
                                            String route,
                                            List<String> bridgeActions,
                                            String sdkVersion) {
        if (session == null) {
            throw new PlatformEmbedTokenException("embed chat session is required");
        }
        if (claims == null || !StringUtils.hasText(claims.getPageInstanceId())) {
            throw new PlatformEmbedTokenException("target embed token pageInstanceId is required");
        }
        if (!StringUtils.hasText(pageInstanceId) || !pageInstanceId.trim().equals(claims.getPageInstanceId())) {
            throw new PlatformEmbedTokenException("pageInstanceId does not match target embed token");
        }
        if (StringUtils.hasText(pageKey) && StringUtils.hasText(claims.getPageKey())
                && !pageKey.trim().equals(claims.getPageKey())) {
            throw new PlatformEmbedTokenException("pageKey does not match target embed token");
        }
        if (StringUtils.hasText(route) && StringUtils.hasText(claims.getRoute())
                && !route.trim().equals(claims.getRoute())) {
            throw new PlatformEmbedTokenException("route does not match target embed token");
        }
        session.setPageKey(StringUtils.hasText(pageKey) ? pageKey.trim() : claims.getPageKey());
        session.setPageInstanceId(pageInstanceId.trim());
        session.setRoute(StringUtils.hasText(route) ? route.trim() : claims.getRoute());
        session.setBridgeActionsJson(writeJson(bridgeActions == null ? List.of() : bridgeActions));
        if (StringUtils.hasText(sdkVersion)) {
            session.setSdkVersion(sdkVersion.trim());
        }
        session.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(session);
    }

    private void requireSamePrincipal(PlatformEmbedSessionEntity entity, PlatformEmbedTokenClaims claims) {
        if (!Objects.equals(entity.getAgentId(), claims.getAgentId())
                || !Objects.equals(entity.getProjectCode(), claims.getProjectCode())
                || !Objects.equals(entity.getExternalUserId(), claims.getExternalUserId())) {
            throw new PlatformEmbedTokenException("embed chat session does not match embed token");
        }
    }

    private void refreshExpiry(PlatformEmbedSessionEntity entity, PlatformEmbedTokenClaims claims) {
        LocalDateTime now = LocalDateTime.now();
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(now)) {
            // A chat session may outlive one short-lived Embed token (for example while the
            // user is reading a confirmation card). The token has already been verified;
            // renew only the same bound user/project/agent session to the fresh token expiry.
            LocalDateTime refreshedExpiry = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(claims.getExpiresAt()), ZoneId.systemDefault());
            if (!refreshedExpiry.isAfter(now)) {
                throw new PlatformEmbedTokenException("embed chat session is expired");
            }
            entity.setExpiresAt(refreshedExpiry);
            entity.setUpdatedAt(now);
            mapper.updateById(entity);
        }
    }

    public void updateBridge(PlatformEmbedSessionEntity session,
                             String pageKey,
                             String pageInstanceId,
                             String route) {
        if (session == null) return;
        if (StringUtils.hasText(pageKey)) session.setPageKey(pageKey.trim());
        if (StringUtils.hasText(pageInstanceId)) session.setPageInstanceId(pageInstanceId.trim());
        if (StringUtils.hasText(route)) session.setRoute(route.trim());
        session.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(session);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }
}
