package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PlatformEmbedCatalogController {

    private final PlatformPageRegistryMapper pageRegistryMapper;
    private final PlatformPageActionRegistryMapper pageActionRegistryMapper;
    private final PlatformEmbedSessionMapper sessionMapper;
    private final PlatformPageActionEventMapper pageActionEventMapper;
    private final PlatformEmbedChatEventMapper chatEventMapper;
    private final PlatformEmbedRendererMapper rendererMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/api/platform/embed/sessions")
    public ResponseEntity<List<EmbedSessionView>> listSessions(@RequestParam(required = false) String appId,
                                                               @RequestParam(required = false) String agentId,
                                                               @RequestParam(required = false) String externalUserId,
                                                               @RequestParam(required = false) String status,
                                                               @RequestParam(defaultValue = "100") int limit) {
        LambdaQueryWrapper<PlatformEmbedSessionEntity> wrapper = new LambdaQueryWrapper<PlatformEmbedSessionEntity>()
                .eq(StringUtils.hasText(appId), PlatformEmbedSessionEntity::getAppId, appId)
                .eq(StringUtils.hasText(agentId), PlatformEmbedSessionEntity::getAgentId, agentId)
                .eq(StringUtils.hasText(externalUserId), PlatformEmbedSessionEntity::getExternalUserId, externalUserId)
                .eq(StringUtils.hasText(status), PlatformEmbedSessionEntity::getStatus, status)
                .orderByDesc(PlatformEmbedSessionEntity::getId)
                .last("limit " + safeLimit(limit, 100));
        return ResponseEntity.ok(sessionMapper.selectList(wrapper).stream()
                .map(this::toSessionView)
                .toList());
    }

    @GetMapping("/api/platform/embed/page-actions")
    public ResponseEntity<List<PageActionEventView>> listPageActionEvents(@RequestParam(required = false) String sessionId,
                                                                          @RequestParam(required = false) String appId,
                                                                          @RequestParam(required = false) String agentId,
                                                                          @RequestParam(required = false) String status,
                                                                          @RequestParam(defaultValue = "100") int limit) {
        LambdaQueryWrapper<PlatformPageActionEventEntity> wrapper =
                new LambdaQueryWrapper<PlatformPageActionEventEntity>()
                        .eq(StringUtils.hasText(sessionId), PlatformPageActionEventEntity::getSessionId, sessionId)
                        .eq(StringUtils.hasText(appId), PlatformPageActionEventEntity::getAppId, appId)
                        .eq(StringUtils.hasText(agentId), PlatformPageActionEventEntity::getAgentId, agentId)
                        .eq(StringUtils.hasText(status), PlatformPageActionEventEntity::getStatus, status)
                        .orderByDesc(PlatformPageActionEventEntity::getId)
                        .last("limit " + safeLimit(limit, 100));
        return ResponseEntity.ok(pageActionEventMapper.selectList(wrapper).stream()
                .map(this::toPageActionEventView)
                .toList());
    }

    @GetMapping({"/api/platform/embed/pages", "/api/platform/embed/pages/catalog"})
    public ResponseEntity<List<PageRegistryView>> listPages(@RequestParam(required = false) String projectCode,
                                                            @RequestParam(defaultValue = "200") int limit) {
        LambdaQueryWrapper<PlatformPageRegistryEntity> wrapper = new LambdaQueryWrapper<PlatformPageRegistryEntity>()
                .eq(StringUtils.hasText(projectCode), PlatformPageRegistryEntity::getProjectCode, projectCode)
                .orderByDesc(PlatformPageRegistryEntity::getLastSeenAt)
                .orderByDesc(PlatformPageRegistryEntity::getId)
                .last("limit " + safeLimit(limit, 200));
        return ResponseEntity.ok(pageRegistryMapper.selectList(wrapper).stream()
                .map(this::toPageView)
                .toList());
    }

    @GetMapping("/api/platform/embed/page-actions/catalog")
    public ResponseEntity<List<PageActionRegistryView>> listPageActions(@RequestParam(required = false) String projectCode,
                                                                        @RequestParam(required = false) String pageKey,
                                                                        @RequestParam(required = false) String status,
                                                                        @RequestParam(defaultValue = "500") int limit) {
        LambdaQueryWrapper<PlatformPageActionRegistryEntity> wrapper =
                new LambdaQueryWrapper<PlatformPageActionRegistryEntity>()
                        .eq(StringUtils.hasText(projectCode), PlatformPageActionRegistryEntity::getProjectCode, projectCode)
                        .eq(StringUtils.hasText(pageKey), PlatformPageActionRegistryEntity::getPageKey, pageKey)
                        .eq(StringUtils.hasText(status), PlatformPageActionRegistryEntity::getStatus, status)
                        .orderByDesc(PlatformPageActionRegistryEntity::getLastSeenAt)
                        .orderByDesc(PlatformPageActionRegistryEntity::getId)
                        .last("limit " + safeLimit(limit, 500));
        return ResponseEntity.ok(pageActionRegistryMapper.selectList(wrapper).stream()
                .map(this::toActionView)
                .toList());
    }

    ResponseEntity<List<PageActionRegistryView>> listPageActions(String projectCode, int limit) {
        return listPageActions(projectCode, null, null, limit);
    }

    @DeleteMapping("/api/platform/embed/pages/{id}")
    public ResponseEntity<PageRegistryDeleteResult> deletePageRegistry(@PathVariable Long id) {
        PlatformPageRegistryEntity page = pageRegistryMapper.selectById(id);
        if (page == null) {
            return ResponseEntity.notFound().build();
        }
        List<String> sessionIds = sessionMapper.selectList(new LambdaQueryWrapper<PlatformEmbedSessionEntity>()
                        .eq(PlatformEmbedSessionEntity::getProjectCode, page.getProjectCode())
                        .eq(PlatformEmbedSessionEntity::getPageKey, page.getPageKey()))
                .stream()
                .map(PlatformEmbedSessionEntity::getSessionId)
                .filter(StringUtils::hasText)
                .toList();
        int deletedActions = pageActionRegistryMapper.delete(new LambdaQueryWrapper<PlatformPageActionRegistryEntity>()
                .eq(PlatformPageActionRegistryEntity::getProjectCode, page.getProjectCode())
                .eq(PlatformPageActionRegistryEntity::getPageKey, page.getPageKey()));
        int deletedPageActionEvents = sessionIds.isEmpty() ? 0 : pageActionEventMapper.delete(
                new LambdaQueryWrapper<PlatformPageActionEventEntity>()
                        .in(PlatformPageActionEventEntity::getSessionId, sessionIds));
        int deletedEmbedChatEvents = sessionIds.isEmpty() ? 0 : chatEventMapper.delete(
                new LambdaQueryWrapper<PlatformEmbedChatEventEntity>()
                        .in(PlatformEmbedChatEventEntity::getSessionId, sessionIds));
        int deletedEmbedSessions = sessionMapper.delete(new LambdaQueryWrapper<PlatformEmbedSessionEntity>()
                .eq(PlatformEmbedSessionEntity::getProjectCode, page.getProjectCode())
                .eq(PlatformEmbedSessionEntity::getPageKey, page.getPageKey()));
        int deletedPages = pageRegistryMapper.deleteById(id);
        return ResponseEntity.ok(new PageRegistryDeleteResult(
                id,
                page.getProjectCode(),
                page.getPageKey(),
                deletedPages,
                deletedActions,
                deletedEmbedSessions,
                deletedPageActionEvents,
                deletedEmbedChatEvents,
                0,
                0));
    }

    @PostMapping("/api/platform/embed/page-actions/catalog/manual")
    public ResponseEntity<PageActionManualDeclareResponse> declarePageActionCatalog(
            @RequestBody PageActionManualDeclarePayload request) {
        String projectCode = requireText(request == null ? null : request.projectCode(), "projectCode");
        String pageKey = requireText(request.pageKey(), "pageKey");
        String actionKey = requireText(request.actionKey(), "actionKey");
        String appId = StringUtils.hasText(request.appId()) ? request.appId().trim() : projectCode;
        LocalDateTime now = LocalDateTime.now();

        PlatformPageRegistryEntity page = new PlatformPageRegistryEntity();
        page.setProjectCode(projectCode);
        page.setAppId(appId);
        page.setPageKey(pageKey);
        page.setName(StringUtils.hasText(request.pageName()) ? request.pageName().trim() : pageKey);
        page.setRoutePattern(StringUtils.hasText(request.routePattern()) ? request.routePattern().trim() : null);
        page.setOrigin("manual");
        page.setStatus("ACTIVE");
        page.setLastSeenAt(now);
        page.setMetadataJson(toJson(Map.of("source", "MANUAL_DRAFT")));
        pageRegistryMapper.insert(page);

        PlatformPageActionRegistryEntity action = new PlatformPageActionRegistryEntity();
        action.setProjectCode(projectCode);
        action.setAppId(appId);
        action.setPageKey(pageKey);
        action.setActionKey(actionKey);
        action.setTitle(StringUtils.hasText(request.title()) ? request.title().trim() : actionKey);
        action.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : null);
        action.setConfirmRequired(Boolean.TRUE.equals(request.confirmRequired()));
        action.setInputSchemaJson(toJson(request.inputSchema() == null ? Map.of() : request.inputSchema()));
        action.setOutputSchemaJson(toJson(request.outputSchema() == null ? Map.of() : request.outputSchema()));
        action.setSampleArgsJson(toJson(request.sampleArgs() == null ? Map.of() : request.sampleArgs()));
        action.setAllowedAgentIdsJson(toJson(request.allowedAgentIds() == null ? List.of() : request.allowedAgentIds()));
        action.setMetadataJson(toJson(Map.of("source", "MANUAL_DRAFT")));
        action.setStatus(StringUtils.hasText(request.status()) ? request.status().trim() : "ACTIVE");
        action.setLastSeenAt(now);
        pageActionRegistryMapper.insert(action);
        return ResponseEntity.ok(new PageActionManualDeclareResponse("MANUAL_DRAFT", toPageView(page), toActionView(action)));
    }

    @PostMapping("/api/registry/projects/{projectCode}/pages/register")
    public ResponseEntity<PageCatalogRegisterResponse> registerPageCatalog(
            @PathVariable String projectCode,
            @RequestBody PageCatalogRegisterPayload request) {
        String normalizedProjectCode = requireText(projectCode, "projectCode");
        String pageKey = requireText(request == null ? null : request.pageKey(), "pageKey");
        String appId = normalizedProjectCode;
        LocalDateTime now = LocalDateTime.now();

        PlatformPageRegistryEntity page = new PlatformPageRegistryEntity();
        page.setProjectCode(normalizedProjectCode);
        page.setAppId(appId);
        page.setPageKey(pageKey);
        page.setName(StringUtils.hasText(request.name()) ? request.name().trim() : pageKey);
        page.setRoutePattern(StringUtils.hasText(request.routePattern()) ? request.routePattern().trim() : null);
        page.setOrigin(StringUtils.hasText(request.origin()) ? request.origin().trim() : null);
        page.setCurrentPageInstanceId(StringUtils.hasText(request.pageInstanceId()) ? request.pageInstanceId().trim() : null);
        page.setStatus("ACTIVE");
        page.setLastSeenAt(now);
        page.setMetadataJson(toJson(pageCatalogMetadata(request.metadata())));
        pageRegistryMapper.insert(page);

        if (Boolean.TRUE.equals(request.replaceActions())) {
            pageActionRegistryMapper.delete(new LambdaQueryWrapper<PlatformPageActionRegistryEntity>()
                    .eq(PlatformPageActionRegistryEntity::getProjectCode, normalizedProjectCode)
                    .eq(PlatformPageActionRegistryEntity::getPageKey, pageKey));
        }
        List<PageActionRegistryView> actions = (request.actions() == null ? List.<PageCatalogActionPayload>of() : request.actions())
                .stream()
                .map(action -> registerPageCatalogAction(normalizedProjectCode, appId, pageKey, action, now))
                .toList();
        return ResponseEntity.ok(new PageCatalogRegisterResponse("SDK", toPageView(page), actions));
    }

    @PostMapping("/api/platform/embed/page-actions/catalog/{id}/debug")
    public ResponseEntity<PageActionDebugResponse> debugPageActionCatalog(@PathVariable Long id,
                                                                          @RequestBody(required = false)
                                                                          PageActionDebugRequest request) {
        PlatformPageActionRegistryEntity action = pageActionRegistryMapper.selectById(id);
        if (action == null) {
            return ResponseEntity.notFound().build();
        }
        PlatformEmbedSessionEntity session = resolveDebugSession(action, request);
        if (session == null) {
            return ResponseEntity.ok(new PageActionDebugResponse(
                    null,
                    null,
                    action.getProjectCode(),
                    action.getPageKey(),
                    action.getActionKey(),
                    null,
                    "NO_ACTIVE_SESSION",
                    "No active embedded session found for this page."));
        }
        String requestId = "debug-" + UUID.randomUUID();
        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setRequestId(requestId);
        event.setSessionId(session.getSessionId());
        event.setTenantId(session.getTenantId());
        event.setAppId(session.getAppId());
        event.setAgentId(session.getAgentId());
        event.setActionKey(action.getActionKey());
        event.setTitle(action.getTitle());
        event.setArgsJson(toJson(request == null || request.args() == null ? Map.of() : request.args()));
        event.setTargetPageInstanceId(session.getPageInstanceId());
        event.setConfirmRequired(false);
        event.setStatus("REQUESTED");
        event.setRequestedAt(LocalDateTime.now());
        pageActionEventMapper.insert(event);
        return ResponseEntity.ok(new PageActionDebugResponse(
                requestId,
                session.getSessionId(),
                action.getProjectCode(),
                action.getPageKey(),
                action.getActionKey(),
                session.getPageInstanceId(),
                "REQUESTED",
                "Debug request has been created."));
    }

    @GetMapping("/api/platform/embed/page-actions/debug/{requestId}")
    public ResponseEntity<PlatformPageActionEventEntity> getPageActionDebugResult(@PathVariable String requestId) {
        PlatformPageActionEventEntity event = pageActionEventMapper.selectOne(
                new LambdaQueryWrapper<PlatformPageActionEventEntity>()
                        .eq(PlatformPageActionEventEntity::getRequestId, requestId)
                        .last("limit 1"));
        return event == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(event);
    }

    @GetMapping("/api/platform/embed/page-actions/catalog/{id}/references")
    public ResponseEntity<List<PageActionReferenceView>> listPageActionReferences(@PathVariable Long id) {
        return pageActionRegistryMapper.selectById(id) == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(List.of());
    }

    @GetMapping("/api/platform/embed/chat-events")
    public ResponseEntity<List<EmbedChatEventView>> listChatEvents(@RequestParam String sessionId,
                                                                   @RequestParam(defaultValue = "200") int limit) {
        LambdaQueryWrapper<PlatformEmbedChatEventEntity> wrapper =
                new LambdaQueryWrapper<PlatformEmbedChatEventEntity>()
                        .eq(PlatformEmbedChatEventEntity::getSessionId, sessionId)
                        .orderByAsc(PlatformEmbedChatEventEntity::getId)
                        .last("limit " + safeLimit(limit, 200));
        return ResponseEntity.ok(chatEventMapper.selectList(wrapper).stream()
                .map(this::toChatEventView)
                .toList());
    }

    @GetMapping("/api/platform/embed/renderers")
    public ResponseEntity<List<EmbedRendererView>> listRenderers(@RequestParam(required = false) String appId,
                                                                 @RequestParam(required = false) String rendererKey,
                                                                 @RequestParam(required = false) String status,
                                                                 @RequestParam(defaultValue = "100") int limit) {
        LambdaQueryWrapper<PlatformEmbedRendererEntity> wrapper =
                new LambdaQueryWrapper<PlatformEmbedRendererEntity>()
                        .eq(StringUtils.hasText(appId), PlatformEmbedRendererEntity::getAppId, appId)
                        .eq(StringUtils.hasText(rendererKey), PlatformEmbedRendererEntity::getRendererKey, rendererKey)
                        .eq(StringUtils.hasText(status), PlatformEmbedRendererEntity::getStatus, status)
                        .orderByDesc(PlatformEmbedRendererEntity::getId)
                        .last("limit " + safeLimit(limit, 100));
        return ResponseEntity.ok(rendererMapper.selectList(wrapper).stream()
                .map(this::toRendererView)
                .toList());
    }

    @PostMapping("/api/platform/embed/renderers")
    public ResponseEntity<EmbedRendererView> createRenderer(@RequestBody EmbedRendererPayload request) {
        PlatformEmbedRendererEntity entity = new PlatformEmbedRendererEntity();
        applyRendererPayload(entity, request);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        rendererMapper.insert(entity);
        return ResponseEntity.ok(toRendererView(entity));
    }

    @PutMapping("/api/platform/embed/renderers/{id}")
    public ResponseEntity<EmbedRendererView> updateRenderer(@PathVariable Long id,
                                                            @RequestBody EmbedRendererPayload request) {
        PlatformEmbedRendererEntity entity = rendererMapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        applyRendererPayload(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        rendererMapper.updateById(entity);
        return ResponseEntity.ok(toRendererView(entity));
    }

    @PostMapping("/api/platform/embed/renderers/{id}/disable")
    public ResponseEntity<Void> disableRenderer(@PathVariable Long id) {
        PlatformEmbedRendererEntity entity = rendererMapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        entity.setStatus("DISABLED");
        entity.setUpdatedAt(LocalDateTime.now());
        rendererMapper.updateById(entity);
        return ResponseEntity.ok().build();
    }

    private int safeLimit(int requested, int fallback) {
        int value = requested <= 0 ? fallback : requested;
        return Math.min(Math.max(value, 1), 1000);
    }

    private PageRegistryView toPageView(PlatformPageRegistryEntity entity) {
        return new PageRegistryView(
                entity.getId(),
                entity.getProjectCode(),
                entity.getAppId(),
                entity.getPageKey(),
                entity.getName(),
                entity.getRoutePattern(),
                entity.getOrigin(),
                entity.getCurrentPageInstanceId(),
                entity.getStatus(),
                instantText(entity.getLastSeenAt()),
                entity.getMetadataJson());
    }

    private PageActionRegistryView toActionView(PlatformPageActionRegistryEntity entity) {
        return new PageActionRegistryView(
                entity.getId(),
                entity.getProjectCode(),
                entity.getAppId(),
                entity.getPageKey(),
                entity.getActionKey(),
                entity.getTitle(),
                entity.getDescription(),
                Boolean.TRUE.equals(entity.getConfirmRequired()),
                entity.getInputSchemaJson(),
                entity.getOutputSchemaJson(),
                entity.getSampleArgsJson(),
                entity.getAllowedAgentIdsJson(),
                entity.getMetadataJson(),
                entity.getStatus(),
                instantText(entity.getLastSeenAt()));
    }

    private EmbedSessionView toSessionView(PlatformEmbedSessionEntity entity) {
        return new EmbedSessionView(
                entity.getId(),
                entity.getSessionId(),
                entity.getTenantId(),
                entity.getAppId(),
                entity.getProjectCode(),
                entity.getAgentId(),
                entity.getExternalUserId(),
                entity.getGlobalUserId(),
                entity.getPageInstanceId(),
                entity.getRoute(),
                entity.getOrigin(),
                entity.getStatus(),
                instantText(entity.getCreatedAt()),
                instantText(entity.getExpiresAt()));
    }

    private PageActionEventView toPageActionEventView(PlatformPageActionEventEntity entity) {
        return new PageActionEventView(
                entity.getId(),
                entity.getRequestId(),
                entity.getSessionId(),
                entity.getAppId(),
                entity.getAgentId(),
                entity.getActionKey(),
                entity.getTitle(),
                entity.getArgsJson(),
                entity.getTargetPageInstanceId(),
                Boolean.TRUE.equals(entity.getConfirmRequired()),
                entity.getStatus(),
                entity.getResultJson(),
                entity.getErrorMessage(),
                instantText(entity.getRequestedAt()),
                instantText(entity.getCompletedAt()));
    }

    private EmbedChatEventView toChatEventView(PlatformEmbedChatEventEntity entity) {
        return new EmbedChatEventView(
                entity.getId(),
                entity.getSessionId(),
                entity.getEventType(),
                entity.getRole(),
                entity.getContent(),
                entity.getPayloadJson(),
                entity.getTraceId(),
                instantText(entity.getCreatedAt()));
    }

    private EmbedRendererView toRendererView(PlatformEmbedRendererEntity entity) {
        return new EmbedRendererView(
                entity.getId(),
                entity.getAppId(),
                entity.getRendererKey(),
                entity.getName(),
                entity.getVersion(),
                entity.getInputSchemaJson(),
                entity.getAllowedAgentIdsJson(),
                entity.getStatus(),
                instantText(entity.getCreatedAt()),
                instantText(entity.getUpdatedAt()));
    }

    private PlatformEmbedSessionEntity resolveDebugSession(PlatformPageActionRegistryEntity action,
                                                           PageActionDebugRequest request) {
        if (request != null && StringUtils.hasText(request.sessionId())) {
            PlatformEmbedSessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<PlatformEmbedSessionEntity>()
                    .eq(PlatformEmbedSessionEntity::getSessionId, request.sessionId())
                    .eq(PlatformEmbedSessionEntity::getStatus, "ACTIVE")
                    .last("limit 1"));
            if (session != null) {
                return session;
            }
        }
        PlatformPageRegistryEntity page = pageRegistryMapper.selectOne(new LambdaQueryWrapper<PlatformPageRegistryEntity>()
                .eq(PlatformPageRegistryEntity::getProjectCode, action.getProjectCode())
                .eq(PlatformPageRegistryEntity::getPageKey, action.getPageKey())
                .eq(PlatformPageRegistryEntity::getStatus, "ACTIVE")
                .last("limit 1"));
        if (page == null || !StringUtils.hasText(page.getCurrentPageInstanceId())) {
            return null;
        }
        return sessionMapper.selectOne(new LambdaQueryWrapper<PlatformEmbedSessionEntity>()
                .eq(PlatformEmbedSessionEntity::getProjectCode, action.getProjectCode())
                .eq(PlatformEmbedSessionEntity::getPageInstanceId, page.getCurrentPageInstanceId())
                .eq(PlatformEmbedSessionEntity::getStatus, "ACTIVE")
                .orderByDesc(PlatformEmbedSessionEntity::getId)
                .last("limit 1"));
    }

    private void applyRendererPayload(PlatformEmbedRendererEntity entity, EmbedRendererPayload request) {
        if (request == null) {
            throw new IllegalArgumentException("renderer request is required");
        }
        entity.setAppId(requireText(request.appId(), "appId"));
        entity.setRendererKey(requireText(request.rendererKey(), "rendererKey"));
        entity.setName(StringUtils.hasText(request.name()) ? request.name().trim() : request.rendererKey().trim());
        entity.setVersion(requireText(request.version(), "version"));
        entity.setInputSchemaJson(toJson(request.inputSchema() == null ? Map.of() : request.inputSchema()));
        entity.setAllowedAgentIdsJson(toJson(request.allowedAgentIds() == null ? List.of() : request.allowedAgentIds()));
        entity.setStatus(StringUtils.hasText(request.status()) ? request.status().trim() : "ACTIVE");
    }

    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private PageActionRegistryView registerPageCatalogAction(String projectCode,
                                                             String appId,
                                                             String pageKey,
                                                             PageCatalogActionPayload request,
                                                             LocalDateTime now) {
        String actionKey = requireText(request == null ? null : request.actionKey(), "actionKey");
        PlatformPageActionRegistryEntity action = new PlatformPageActionRegistryEntity();
        action.setProjectCode(projectCode);
        action.setAppId(appId);
        action.setPageKey(pageKey);
        action.setActionKey(actionKey);
        action.setTitle(StringUtils.hasText(request.title()) ? request.title().trim() : actionKey);
        action.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : null);
        action.setConfirmRequired(Boolean.TRUE.equals(request.confirmRequired()));
        action.setInputSchemaJson(toJson(request.inputSchema() == null ? Map.of() : request.inputSchema()));
        action.setOutputSchemaJson(toJson(request.outputSchema() == null ? Map.of() : request.outputSchema()));
        action.setSampleArgsJson(toJson(request.sampleArgs() == null ? Map.of() : request.sampleArgs()));
        action.setAllowedAgentIdsJson(toJson(request.allowedAgentIds() == null ? List.of() : request.allowedAgentIds()));
        action.setMetadataJson(toJson(pageCatalogMetadata(request.metadata())));
        action.setStatus("ACTIVE");
        action.setLastSeenAt(now);
        pageActionRegistryMapper.insert(action);
        return toActionView(action);
    }

    private Map<String, Object> pageCatalogMetadata(Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "SDK");
        if (metadata != null) {
            result.putAll(metadata);
        }
        return result;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("embed payload json is invalid", ex);
        }
    }

    private String instantText(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    record EmbedSessionView(
            Long id,
            String sessionId,
            String tenantId,
            String appId,
            String projectCode,
            String agentId,
            String externalUserId,
            String globalUserId,
            String pageInstanceId,
            String route,
            String origin,
            String status,
            String createdAt,
            String expiresAt
    ) {
    }

    record PageActionEventView(
            Long id,
            String requestId,
            String sessionId,
            String appId,
            String agentId,
            String actionKey,
            String title,
            String argsJson,
            String targetPageInstanceId,
            boolean confirmRequired,
            String status,
            String resultJson,
            String errorMessage,
            String requestedAt,
            String completedAt
    ) {
    }

    record EmbedChatEventView(
            Long id,
            String sessionId,
            String eventType,
            String role,
            String content,
            String payloadJson,
            String traceId,
            String createdAt
    ) {
    }

    record EmbedRendererView(
            Long id,
            String appId,
            String rendererKey,
            String name,
            String version,
            String inputSchemaJson,
            String allowedAgentIdsJson,
            String status,
            String createdAt,
            String updatedAt
    ) {
    }

    public record EmbedRendererPayload(
            String appId,
            String rendererKey,
            String name,
            String version,
            Map<String, Object> inputSchema,
            List<String> allowedAgentIds,
            String status
    ) {
    }

    public record PageActionManualDeclarePayload(
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
            String status
    ) {
    }

    public record PageActionManualDeclareResponse(
            String source,
            PageRegistryView page,
            PageActionRegistryView action
    ) {
    }

    public record PageCatalogRegisterPayload(
            String pageKey,
            String name,
            String routePattern,
            String origin,
            String pageInstanceId,
            Boolean replaceActions,
            List<PageCatalogActionPayload> actions,
            Map<String, Object> metadata
    ) {
    }

    public record PageCatalogActionPayload(
            String actionKey,
            String title,
            String description,
            Boolean confirmRequired,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            Map<String, Object> sampleArgs,
            List<String> allowedAgentIds,
            Map<String, Object> metadata
    ) {
    }

    public record PageCatalogRegisterResponse(
            String source,
            PageRegistryView page,
            List<PageActionRegistryView> actions
    ) {
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
            String message
    ) {
    }

    record PageActionReferenceView(
            String referenceKey,
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
            String actionKey
    ) {
    }

    record PageRegistryView(
            Long id,
            String projectCode,
            String appId,
            String pageKey,
            String name,
            String routePattern,
            String origin,
            String currentPageInstanceId,
            String status,
            String lastSeenAt,
            String metadataJson
    ) {
    }

    record PageActionRegistryView(
            Long id,
            String projectCode,
            String appId,
            String pageKey,
            String actionKey,
            String title,
            String description,
            boolean confirmRequired,
            String inputSchemaJson,
            String outputSchemaJson,
            String sampleArgsJson,
            String allowedAgentIdsJson,
            String metadataJson,
            String status,
            String lastSeenAt
    ) {
    }

    public record PageRegistryDeleteResult(
            Long pageId,
            String projectCode,
            String pageKey,
            int deletedPages,
            int deletedActions,
            int deletedEmbedSessions,
            int deletedPageActionEvents,
            int deletedEmbedChatEvents,
            int deletedAccessSessions,
            int deletedAccessSteps
    ) {
    }
}
