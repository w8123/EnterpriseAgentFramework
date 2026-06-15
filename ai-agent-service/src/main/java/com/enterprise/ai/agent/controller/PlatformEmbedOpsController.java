package com.enterprise.ai.agent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.identity.EmbedChatEventEntity;
import com.enterprise.ai.agent.identity.EmbedChatEventMapper;
import com.enterprise.ai.agent.identity.EmbedAuditEventService;
import com.enterprise.ai.agent.identity.EmbedRendererEntity;
import com.enterprise.ai.agent.identity.EmbedRendererMapper;
import com.enterprise.ai.agent.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.identity.EmbedSessionMapper;
import com.enterprise.ai.agent.identity.EmbedTokenRevocationService;
import com.enterprise.ai.agent.identity.PageActionEventEntity;
import com.enterprise.ai.agent.identity.PageActionEventMapper;
import com.enterprise.ai.agent.identity.PageActionRegistryEntity;
import com.enterprise.ai.agent.identity.PageActionRegistryMapper;
import com.enterprise.ai.agent.identity.PageRegistryEntity;
import com.enterprise.ai.agent.identity.PageRegistryMapper;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistryCredentialMapper;
import com.enterprise.ai.agent.workflow.WorkflowActionReferenceService;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
    private final PageRegistryMapper pageRegistryMapper;
    private final PageActionRegistryMapper pageActionRegistryMapper;
    private final WorkflowActionReferenceService workflowActionReferenceService;
    private final EmbedAuditEventService embedAuditEventService;
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

    @GetMapping("/pages")
    public ApiResult<List<PageRegistryEntity>> pages(@RequestParam(required = false) String projectCode,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(defaultValue = "100") Integer limit) {
        LambdaQueryWrapper<PageRegistryEntity> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(projectCode)) query.eq(PageRegistryEntity::getProjectCode, projectCode);
        if (StringUtils.hasText(status)) query.eq(PageRegistryEntity::getStatus, status);
        query.orderByDesc(PageRegistryEntity::getLastSeenAt).orderByDesc(PageRegistryEntity::getId).last("LIMIT " + safeLimit(limit));
        return ApiResult.ok(pageRegistryMapper.selectList(query));
    }

    @GetMapping("/page-actions/catalog")
    public ApiResult<List<PageActionRegistryEntity>> pageActionCatalog(@RequestParam(required = false) String projectCode,
                                                                       @RequestParam(required = false) String pageKey,
                                                                       @RequestParam(required = false) String status,
                                                                       @RequestParam(defaultValue = "200") Integer limit) {
        LambdaQueryWrapper<PageActionRegistryEntity> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(projectCode)) query.eq(PageActionRegistryEntity::getProjectCode, projectCode);
        if (StringUtils.hasText(pageKey)) query.eq(PageActionRegistryEntity::getPageKey, pageKey);
        if (StringUtils.hasText(status)) query.eq(PageActionRegistryEntity::getStatus, status);
        query.orderByDesc(PageActionRegistryEntity::getLastSeenAt).orderByDesc(PageActionRegistryEntity::getId).last("LIMIT " + safeLimit(limit));
        return ApiResult.ok(pageActionRegistryMapper.selectList(query));
    }

    @PostMapping("/page-actions/catalog/manual")
    public ApiResult<PageActionManualDeclareResponse> declarePageActionCatalog(@RequestBody PageActionManualDeclareRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("page action declaration request is required");
        }
        String projectCode = requireText(request.projectCode(), "projectCode");
        String pageKey = requireText(request.pageKey(), "pageKey");
        String actionKey = requireText(request.actionKey(), "actionKey");
        String appId = StringUtils.hasText(request.appId()) ? request.appId().trim() : projectCode;
        LocalDateTime now = LocalDateTime.now();

        PageRegistryEntity page = new PageRegistryEntity();
        page.setProjectCode(projectCode);
        page.setAppId(appId);
        page.setPageKey(pageKey);
        page.setName(StringUtils.hasText(request.pageName()) ? request.pageName().trim() : pageKey);
        page.setRoutePattern(StringUtils.hasText(request.routePattern()) ? request.routePattern().trim() : "");
        page.setOrigin("manual");
        page.setStatus("ACTIVE");
        page.setLastSeenAt(now);
        page.setMetadataJson(toJson(Map.of("source", "MANUAL_DRAFT")));
        page.setCreatedAt(now);
        page.setUpdatedAt(now);
        pageRegistryMapper.upsert(page);

        PageActionRegistryEntity action = new PageActionRegistryEntity();
        action.setProjectCode(projectCode);
        action.setAppId(appId);
        action.setPageKey(pageKey);
        action.setActionKey(actionKey);
        action.setTitle(StringUtils.hasText(request.title()) ? request.title().trim() : actionKey);
        action.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : "");
        action.setConfirmRequired(Boolean.TRUE.equals(request.confirmRequired()));
        action.setInputSchemaJson(toJson(request.inputSchema() == null ? Map.of() : request.inputSchema()));
        action.setOutputSchemaJson(toJson(request.outputSchema() == null ? Map.of() : request.outputSchema()));
        action.setSampleArgsJson(toJson(request.sampleArgs() == null ? Map.of() : request.sampleArgs()));
        action.setAllowedAgentIdsJson(toJson(request.allowedAgentIds() == null ? List.of() : request.allowedAgentIds()));
        action.setMetadataJson(toJson(Map.of("source", "MANUAL_DRAFT")));
        action.setStatus(StringUtils.hasText(request.status()) ? request.status().trim() : "ACTIVE");
        action.setLastSeenAt(now);
        action.setCreatedAt(now);
        action.setUpdatedAt(now);
        pageActionRegistryMapper.upsert(action);

        return ApiResult.ok(new PageActionManualDeclareResponse("MANUAL_DRAFT", page, action));
    }

    @GetMapping("/page-actions/catalog/{id}/references")
    public ApiResult<List<PageActionReferenceView>> pageActionReferences(@PathVariable Long id) {
        PageActionRegistryEntity action = pageActionRegistryMapper.selectById(id);
        if (action == null) {
            throw new IllegalArgumentException("page action catalog not found: " + id);
        }
        return ApiResult.ok(workflowActionReferenceService.findReferences(action)
                .stream()
                .map(PageActionReferenceView::from)
                .toList());
    }

    @PostMapping("/page-actions/catalog/{id}/debug")
    public ApiResult<PageActionDebugResponse> debugPageAction(@PathVariable Long id,
                                                              @RequestBody PageActionDebugRequest request) {
        PageActionRegistryEntity action = pageActionRegistryMapper.selectById(id);
        if (action == null) {
            throw new IllegalArgumentException("page action catalog not found: " + id);
        }
        if (!"ACTIVE".equalsIgnoreCase(action.getStatus())) {
            throw new IllegalArgumentException("page action is not ACTIVE: " + action.getActionKey());
        }
        EmbedSessionEntity session = resolveDebugSession(action, request);
        if (session == null) {
            return ApiResult.ok(new PageActionDebugResponse(
                    null,
                    null,
                    action.getProjectCode(),
                    action.getPageKey(),
                    action.getActionKey(),
                    null,
                    "NO_ACTIVE_SESSION",
                    "没有找到该页面当前在线的嵌入式会话，请先打开业务页面并初始化 ReachAI 前端 SDK。"));
        }
        String requestId = "debug-" + UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "page.action.requested");
        payload.put("protocolVersion", "1.0");
        payload.put("requestId", requestId);
        payload.put("actionKey", action.getActionKey());
        payload.put("title", StringUtils.hasText(action.getTitle()) ? action.getTitle() : action.getActionKey());
        payload.put("args", request == null || request.args() == null ? Map.of() : request.args());
        payload.put("confirm", false);
        payload.put("debug", true);
        payload.put("target", Map.of("pageInstanceId", session.getPageInstanceId()));
        embedAuditEventService.recordPageActionDebugRequest(session, payload);
        return ApiResult.ok(new PageActionDebugResponse(
                requestId,
                session.getSessionId(),
                action.getProjectCode(),
                action.getPageKey(),
                action.getActionKey(),
                session.getPageInstanceId(),
                "REQUESTED",
                "调试请求已创建，业务页面 SDK 轮询到请求后会执行并回传结果。"));
    }

    @GetMapping("/page-actions/debug/{requestId}")
    public ApiResult<PageActionEventEntity> pageActionDebugResult(@PathVariable String requestId) {
        if (!StringUtils.hasText(requestId)) {
            throw new IllegalArgumentException("requestId is required");
        }
        PageActionEventEntity event = pageActionEventMapper.selectOne(Wrappers.<PageActionEventEntity>lambdaQuery()
                .eq(PageActionEventEntity::getRequestId, requestId)
                .last("LIMIT 1"));
        if (event == null) {
            throw new IllegalArgumentException("page action debug request not found: " + requestId);
        }
        return ApiResult.ok(event);
    }

    private EmbedSessionEntity resolveDebugSession(PageActionRegistryEntity action, PageActionDebugRequest request) {
        if (request != null && StringUtils.hasText(request.sessionId())) {
            EmbedSessionEntity session = sessionMapper.selectOne(Wrappers.<EmbedSessionEntity>lambdaQuery()
                    .eq(EmbedSessionEntity::getSessionId, request.sessionId())
                    .eq(EmbedSessionEntity::getStatus, "ACTIVE")
                    .last("LIMIT 1"));
            if (session != null) {
                return session;
            }
        }
        PageRegistryEntity page = pageRegistryMapper.selectOne(Wrappers.<PageRegistryEntity>lambdaQuery()
                .eq(PageRegistryEntity::getProjectCode, action.getProjectCode())
                .eq(PageRegistryEntity::getPageKey, action.getPageKey())
                .eq(PageRegistryEntity::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (page == null || !StringUtils.hasText(page.getCurrentPageInstanceId())) {
            return null;
        }
        return sessionMapper.selectOne(Wrappers.<EmbedSessionEntity>lambdaQuery()
                .eq(EmbedSessionEntity::getProjectCode, action.getProjectCode())
                .eq(EmbedSessionEntity::getPageInstanceId, page.getCurrentPageInstanceId())
                .eq(EmbedSessionEntity::getStatus, "ACTIVE")
                .orderByDesc(EmbedSessionEntity::getId)
                .last("LIMIT 1"));
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

    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
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

    public record PageActionManualDeclareRequest(
            String projectCode,
            String appId,
            String pageKey,
            String pageName,
            String routePattern,
            String actionKey,
            String title,
            String description,
            Boolean confirmRequired,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            Map<String, Object> sampleArgs,
            List<String> allowedAgentIds,
            String status) {
    }

    public record PageActionManualDeclareResponse(
            String source,
            PageRegistryEntity page,
            PageActionRegistryEntity action) {
    }

    public record PageActionDebugRequest(String sessionId, Map<String, Object> args) {
    }

    public record PageActionDebugResponse(
            String requestId,
            String sessionId,
            String projectCode,
            String pageKey,
            String actionKey,
            String targetPageInstanceId,
            String status,
            String message) {
    }

    public record PageActionReferenceView(
            String agentId,
            String agentName,
            String agentKeySlug,
            String agentProjectCode,
            Boolean agentEnabled,
            String workflowId,
            String workflowKeySlug,
            String workflowName,
            String workflowProjectCode,
            String workflowStatus,
            Long workflowVersionId,
            String workflowVersion,
            String graphSource,
            Long bindingId,
            String bindingType,
            Boolean bindingEnabled,
            String nodeId,
            String nodeName,
            String projectCode,
            String pageKey,
            String actionKey) {
        static PageActionReferenceView from(WorkflowActionReferenceService.PageActionWorkflowReference reference) {
            return new PageActionReferenceView(
                    reference.entryAgentId(),
                    reference.entryAgentName(),
                    reference.entryAgentKeySlug(),
                    reference.entryAgentProjectCode(),
                    reference.entryAgentEnabled(),
                    reference.workflowId(),
                    reference.workflowKeySlug(),
                    reference.workflowName(),
                    reference.workflowProjectCode(),
                    reference.workflowStatus(),
                    reference.workflowVersionId(),
                    reference.workflowVersion(),
                    reference.graphSource(),
                    reference.bindingId(),
                    reference.bindingType(),
                    reference.bindingEnabled(),
                    reference.nodeId(),
                    reference.nodeName(),
                    reference.projectCode(),
                    reference.pageKey(),
                    reference.actionKey());
        }
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
