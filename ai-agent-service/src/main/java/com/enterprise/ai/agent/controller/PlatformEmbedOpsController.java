package com.enterprise.ai.agent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.identity.EmbedChatEventEntity;
import com.enterprise.ai.agent.identity.EmbedChatEventMapper;
import com.enterprise.ai.agent.identity.EmbedRendererEntity;
import com.enterprise.ai.agent.identity.EmbedRendererMapper;
import com.enterprise.ai.agent.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.identity.EmbedSessionMapper;
import com.enterprise.ai.agent.identity.EmbedTokenRevocationService;
import com.enterprise.ai.agent.identity.PageActionEventEntity;
import com.enterprise.ai.agent.identity.PageActionEventMapper;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistryCredentialMapper;
import com.enterprise.ai.common.dto.ApiResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/platform/embed")
@RequiredArgsConstructor
public class PlatformEmbedOpsController {

    private final EmbedSessionMapper sessionMapper;
    private final PageActionEventMapper pageActionEventMapper;
    private final EmbedChatEventMapper chatEventMapper;
    private final EmbedTokenRevocationService revocationService;
    private final EmbedRendererMapper rendererMapper;
    private final RegistryCredentialMapper credentialMapper;
    private final ObjectMapper objectMapper;

    @GetMapping("/sessions")
    public ApiResult<List<EmbedSessionEntity>> sessions(@RequestParam(required = false) String appId,
                                                        @RequestParam(required = false) String agentId,
                                                        @RequestParam(required = false) String externalUserId,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "100") Integer limit) {
        LambdaQueryWrapper<EmbedSessionEntity> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(appId)) query.eq(EmbedSessionEntity::getAppId, appId);
        if (StringUtils.hasText(agentId)) query.eq(EmbedSessionEntity::getAgentId, agentId);
        if (StringUtils.hasText(externalUserId)) query.eq(EmbedSessionEntity::getExternalUserId, externalUserId);
        if (StringUtils.hasText(status)) query.eq(EmbedSessionEntity::getStatus, status);
        query.orderByDesc(EmbedSessionEntity::getId).last("LIMIT " + safeLimit(limit));
        return ApiResult.ok(sessionMapper.selectList(query));
    }

    @GetMapping("/page-actions")
    public ApiResult<List<PageActionEventEntity>> pageActions(@RequestParam(required = false) String sessionId,
                                                              @RequestParam(required = false) String appId,
                                                              @RequestParam(required = false) String agentId,
                                                              @RequestParam(required = false) String status,
                                                              @RequestParam(defaultValue = "100") Integer limit) {
        LambdaQueryWrapper<PageActionEventEntity> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(sessionId)) query.eq(PageActionEventEntity::getSessionId, sessionId);
        if (StringUtils.hasText(appId)) query.eq(PageActionEventEntity::getAppId, appId);
        if (StringUtils.hasText(agentId)) query.eq(PageActionEventEntity::getAgentId, agentId);
        if (StringUtils.hasText(status)) query.eq(PageActionEventEntity::getStatus, status);
        query.orderByDesc(PageActionEventEntity::getId).last("LIMIT " + safeLimit(limit));
        return ApiResult.ok(pageActionEventMapper.selectList(query));
    }

    @GetMapping("/chat-events")
    public ApiResult<List<EmbedChatEventEntity>> chatEvents(@RequestParam String sessionId,
                                                            @RequestParam(defaultValue = "200") Integer limit) {
        LambdaQueryWrapper<EmbedChatEventEntity> query = Wrappers.<EmbedChatEventEntity>lambdaQuery()
                .eq(EmbedChatEventEntity::getSessionId, sessionId)
                .orderByAsc(EmbedChatEventEntity::getId)
                .last("LIMIT " + safeLimit(limit));
        return ApiResult.ok(chatEventMapper.selectList(query));
    }

    @GetMapping("/credentials")
    public ApiResult<List<CredentialPolicyView>> credentials(@RequestParam(required = false) String projectCode,
                                                             @RequestParam(required = false) String status,
                                                             @RequestParam(defaultValue = "100") Integer limit) {
        LambdaQueryWrapper<RegistryCredentialEntity> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(projectCode)) query.eq(RegistryCredentialEntity::getProjectCode, projectCode);
        if (StringUtils.hasText(status)) query.eq(RegistryCredentialEntity::getStatus, status);
        query.orderByDesc(RegistryCredentialEntity::getId).last("LIMIT " + safeLimit(limit));
        return ApiResult.ok(credentialMapper.selectList(query).stream()
                .map(CredentialPolicyView::from)
                .toList());
    }

    @PutMapping("/credentials/{id}/policy")
    public ApiResult<CredentialPolicyView> updateCredentialPolicy(@PathVariable Long id,
                                                                  @RequestBody CredentialPolicyRequest request) {
        RegistryCredentialEntity entity = credentialMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("registry credential not found: " + id);
        }
        List<String> allowedOrigins = request == null || request.allowedOrigins() == null
                ? List.of()
                : request.allowedOrigins();
        if (allowedOrigins.stream().anyMatch(origin -> "*".equals(origin == null ? "" : origin.trim()))) {
            throw new IllegalArgumentException("embed origin policy does not allow naked *");
        }
        entity.setAllowedOriginsJson(toJson(allowedOrigins));
        entity.setAllowedAgentIdsJson(toJson(request == null || request.allowedAgentIds() == null
                ? List.of()
                : request.allowedAgentIds()));
        entity.setTokenTtlSeconds(request == null || request.tokenTtlSeconds() == null
                ? 600
                : Math.max(60, Math.min(request.tokenTtlSeconds(), 3600)));
        if (request != null && StringUtils.hasText(request.status())) {
            entity.setStatus(request.status().trim());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        credentialMapper.updateById(entity);
        return ApiResult.ok(CredentialPolicyView.from(entity));
    }

    @GetMapping("/renderers")
    public ApiResult<List<EmbedRendererEntity>> renderers(@RequestParam(required = false) String appId,
                                                          @RequestParam(required = false) String rendererKey,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(defaultValue = "100") Integer limit) {
        LambdaQueryWrapper<EmbedRendererEntity> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(appId)) query.eq(EmbedRendererEntity::getAppId, appId);
        if (StringUtils.hasText(rendererKey)) query.eq(EmbedRendererEntity::getRendererKey, rendererKey);
        if (StringUtils.hasText(status)) query.eq(EmbedRendererEntity::getStatus, status);
        query.orderByDesc(EmbedRendererEntity::getId).last("LIMIT " + safeLimit(limit));
        return ApiResult.ok(rendererMapper.selectList(query));
    }

    @PostMapping("/renderers")
    public ApiResult<EmbedRendererEntity> createRenderer(@RequestBody RendererRequest request) {
        EmbedRendererEntity entity = new EmbedRendererEntity();
        applyRendererRequest(entity, request);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        rendererMapper.insert(entity);
        return ApiResult.ok(entity);
    }

    @PutMapping("/renderers/{id}")
    public ApiResult<EmbedRendererEntity> updateRenderer(@PathVariable Long id, @RequestBody RendererRequest request) {
        EmbedRendererEntity entity = rendererMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("embed renderer not found: " + id);
        }
        applyRendererRequest(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        rendererMapper.updateById(entity);
        return ApiResult.ok(entity);
    }

    @PostMapping("/tokens/revoke")
    public ApiResult<Void> revokeToken(@RequestBody RevokeTokenRequest request) {
        revocationService.revoke(
                request.jti(),
                request.expiresAtEpochSeconds() == null ? null : Instant.ofEpochSecond(request.expiresAtEpochSeconds()),
                request.reason());
        return ApiResult.ok();
    }

    @PostMapping("/renderers/{id}/disable")
    public ApiResult<Void> disableRenderer(@PathVariable Long id) {
        EmbedRendererEntity entity = rendererMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("embed renderer not found: " + id);
        }
        entity.setStatus("DISABLED");
        entity.setUpdatedAt(LocalDateTime.now());
        rendererMapper.updateById(entity);
        return ApiResult.ok();
    }

    private void applyRendererRequest(EmbedRendererEntity entity, RendererRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("renderer request is required");
        }
        requireText(request.appId(), "appId");
        requireText(request.rendererKey(), "rendererKey");
        requireText(request.version(), "version");
        entity.setAppId(request.appId().trim());
        entity.setRendererKey(request.rendererKey().trim());
        entity.setName(StringUtils.hasText(request.name()) ? request.name().trim() : request.rendererKey().trim());
        entity.setVersion(request.version().trim());
        entity.setInputSchemaJson(toJson(request.inputSchema() == null ? Map.of() : request.inputSchema()));
        entity.setAllowedAgentIdsJson(toJson(request.allowedAgentIds() == null ? List.of() : request.allowedAgentIds()));
        entity.setStatus(StringUtils.hasText(request.status()) ? request.status().trim() : "ACTIVE");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("renderer json is invalid", ex);
        }
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private int safeLimit(Integer limit) {
        return Math.max(1, Math.min(limit == null ? 100 : limit, 500));
    }

    public record RevokeTokenRequest(String jti, Long expiresAtEpochSeconds, String reason) {
    }

    public record RendererRequest(
            String appId,
            String rendererKey,
            String name,
            String version,
            Map<String, Object> inputSchema,
            List<String> allowedAgentIds,
            String status) {
    }

    public record CredentialPolicyRequest(
            List<String> allowedOrigins,
            List<String> allowedAgentIds,
            Integer tokenTtlSeconds,
            String status) {
    }

    public record CredentialPolicyView(
            Long id,
            Long projectId,
            String projectCode,
            String appKey,
            String allowedOriginsJson,
            String allowedAgentIdsJson,
            Integer tokenTtlSeconds,
            String status) {
        static CredentialPolicyView from(RegistryCredentialEntity entity) {
            return new CredentialPolicyView(
                    entity.getId(),
                    entity.getProjectId(),
                    entity.getProjectCode(),
                    entity.getAppKey(),
                    entity.getAllowedOriginsJson(),
                    entity.getAllowedAgentIdsJson(),
                    entity.getTokenTtlSeconds(),
                    entity.getStatus());
        }
    }
}
