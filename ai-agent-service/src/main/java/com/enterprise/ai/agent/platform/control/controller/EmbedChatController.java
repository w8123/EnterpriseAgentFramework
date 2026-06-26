package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.platform.control.context.memory.RuntimeMemoryCandidateIdentity;
import com.enterprise.ai.agent.platform.control.context.memory.RuntimeMemoryCandidateResult;
import com.enterprise.ai.agent.platform.control.context.memory.RuntimeMemoryCandidateService;
import com.enterprise.ai.agent.platform.control.context.ContextPackageResponse;
import com.enterprise.ai.agent.platform.control.context.ContextSearchResult;
import com.enterprise.ai.agent.platform.control.context.runtime.RuntimeContextInjectionResult;
import com.enterprise.ai.agent.platform.control.context.runtime.RuntimeContextPackageService;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.platform.control.identity.BusinessPrincipal;
import com.enterprise.ai.agent.platform.control.identity.BusinessUserBatchSyncResult;
import com.enterprise.ai.agent.platform.control.identity.BusinessUserDirectoryService;
import com.enterprise.ai.agent.platform.control.identity.BusinessUserSyncCommand;
import com.enterprise.ai.agent.platform.control.identity.BusinessUserSyncResult;
import com.enterprise.ai.agent.platform.control.identity.EmbedAuditEventService;
import com.enterprise.ai.agent.platform.control.identity.EmbedChatStreamEvent;
import com.enterprise.ai.agent.platform.control.identity.EmbedChatStreamEventFactory;
import com.enterprise.ai.agent.platform.control.identity.EmbedRendererAuthorizationService;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionService;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenClaims;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenException;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenIssueCommand;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenIssueResult;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenService;
import com.enterprise.ai.agent.platform.control.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.ChatResponse;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.EmbedWorkflowRuntimeService;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeRequest;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeService;
import com.enterprise.ai.common.dto.ApiResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/embed")
@RequiredArgsConstructor
public class EmbedChatController {

    private static final String ACTIVE_WORKFLOW_VERSION_REQUIRED = "active workflow version is required";
    private static final String EMBED_CHAT_SESSION_EXPIRED = "embed chat session is expired";
    private static final String EMBED_TOKEN_EXPIRED = "embed token is expired";
    private static final String WORKFLOW_NOT_PUBLISHED_MESSAGE = "工作流尚未发布，请到 Workflow Studio 发布";
    private static final String EMBED_SECRET_EXPIRED_MESSAGE = "秘钥超时，请刷新页面后重试";
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final RegistrySecurityService registrySecurityService;
    private final EmbedTokenService embedTokenService;
    private final BusinessUserDirectoryService businessUserDirectoryService;
    private final EmbedSessionService embedSessionService;
    private final AgentEntryService agentEntryService;
    private final AgentRouter agentRouter;
    private final ObjectMapper objectMapper;
    private final GuardDecisionLogService guardDecisionLogService;
    private final EmbedAuditEventService embedAuditEventService;
    private final EmbedRendererAuthorizationService embedRendererAuthorizationService;
    private final EmbedWorkflowRuntimeService embedWorkflowRuntimeService;
    private final WorkflowRuntimeService workflowRuntimeService;
    private final RuntimeContextPackageService runtimeContextPackageService;
    private final RuntimeMemoryCandidateService runtimeMemoryCandidateService;

    @PostMapping("/token/exchange")
    public ResponseEntity<ApiResult<EmbedTokenExchangeResponse>> exchangeToken(
            @RequestBody EmbedTokenExchangeRequest request,
            HttpServletRequest servletRequest) {
        try {
            requireText(request.projectCode(), "projectCode");
            requireText(request.agentId(), "agentId");
            requireText(request.pageInstanceId(), "pageInstanceId");
            requireText(request.origin(), "origin");
            BusinessPrincipal principal = request.principal();
            if (principal == null || !StringUtils.hasText(principal.getExternalUserId())) {
                throw new IllegalArgumentException("principal.externalUserId is required");
            }

            RegistryCredentialEntity credential = registrySecurityService.verifyRequired(
                    request.projectCode(),
                    signatureHeaders(servletRequest));
            ensureOriginAllowed(credential, request.origin());
            ensureAgentAllowed(credential, request.projectCode(), request.agentId());

            String tenantId = StringUtils.hasText(principal.getTenantId()) ? principal.getTenantId() : "default";
            String appId = request.projectCode();
            principal.setTenantId(tenantId);
            principal.setAppId(appId);
            if (!StringUtils.hasText(principal.getGlobalUserId())) {
                principal.setGlobalUserId(principal.getExternalUserId());
            }
            businessUserDirectoryService.upsertFromPrincipal(principal);

            EmbedTokenIssueResult token = embedTokenService.issue(EmbedTokenIssueCommand.builder()
                    .tenantId(tenantId)
                    .appId(appId)
                    .projectCode(request.projectCode())
                    .agentId(request.agentId())
                    .pageKey(request.pageKey())
                    .pageInstanceId(request.pageInstanceId())
                    .route(request.route())
                    .origin(request.origin())
                    .ttlSeconds(credential.getTokenTtlSeconds())
                    .principal(principal)
                    .build());
            EmbedTokenExchangeResponse response = new EmbedTokenExchangeResponse(
                    token.token(),
                    token.expiresIn(),
                    Map.of(
                            "appId", appId,
                            "agentId", request.agentId(),
                            "pageKey", StringUtils.hasText(request.pageKey()) ? request.pageKey() : "",
                            "pageInstanceId", request.pageInstanceId()));
            return ResponseEntity.ok(ApiResult.ok(response));
        } catch (IllegalArgumentException | EmbedTokenException ex) {
            recordEmbedDeny(
                    "EMBED_TOKEN",
                    "AGENT",
                    request == null ? null : request.agentId(),
                    ex.getMessage(),
                    tokenExchangeMetadata(request, servletRequest));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail(403, ex.getMessage()));
        }
    }

    @PostMapping("/users/{projectCode}")
    public ResponseEntity<ApiResult<BusinessUserSyncResult>> upsertUser(
            @PathVariable String projectCode,
            @RequestBody BusinessUserSyncCommand request,
            HttpServletRequest servletRequest) {
        try {
            RegistryCredentialEntity credential = registrySecurityService.verifyRequired(projectCode, signatureHeaders(servletRequest));
            BusinessUserSyncResult result = businessUserDirectoryService.upsertExternalUser(
                    "default",
                    credential.getProjectCode(),
                    request,
                    "SDK_SYNC");
            return ResponseEntity.ok(ApiResult.ok(result));
        } catch (IllegalArgumentException ex) {
            recordEmbedDeny(
                    "EMBED_USER_SYNC",
                    "PROJECT",
                    projectCode,
                    ex.getMessage(),
                    Map.of("projectCode", projectCode, "operation", "UPSERT"));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail(403, ex.getMessage()));
        }
    }

    @PostMapping("/users/{projectCode}/sync")
    public ResponseEntity<ApiResult<BusinessUserBatchSyncResult>> syncUsers(
            @PathVariable String projectCode,
            @RequestBody BusinessUserBatchSyncRequest request,
            HttpServletRequest servletRequest) {
        try {
            RegistryCredentialEntity credential = registrySecurityService.verifyRequired(projectCode, signatureHeaders(servletRequest));
            BusinessUserBatchSyncResult result = businessUserDirectoryService.syncExternalUsers(
                    "default",
                    credential.getProjectCode(),
                    request.users(),
                    "SDK_SYNC");
            return ResponseEntity.ok(ApiResult.ok(result));
        } catch (IllegalArgumentException ex) {
            recordEmbedDeny(
                    "EMBED_USER_SYNC",
                    "PROJECT",
                    projectCode,
                    ex.getMessage(),
                    Map.of("projectCode", projectCode, "operation", "BATCH_SYNC"));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail(403, ex.getMessage()));
        }
    }

    @PostMapping("/users/{projectCode}/{externalUserId}/disable")
    public ResponseEntity<ApiResult<BusinessUserSyncResult>> disableUser(
            @PathVariable String projectCode,
            @PathVariable String externalUserId,
            HttpServletRequest servletRequest) {
        return markUser(projectCode, externalUserId, servletRequest, true);
    }

    @PostMapping("/users/{projectCode}/{externalUserId}/delete")
    public ResponseEntity<ApiResult<BusinessUserSyncResult>> deleteUser(
            @PathVariable String projectCode,
            @PathVariable String externalUserId,
            HttpServletRequest servletRequest) {
        return markUser(projectCode, externalUserId, servletRequest, false);
    }

    @PostMapping("/chat/sessions")
    public ResponseEntity<ApiResult<EmbedChatSessionResponse>> createSession(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody EmbedChatSessionCreateRequest request) {
        try {
            EmbedTokenClaims claims = verifyBearer(authorization);
            EmbedSessionEntity session = embedSessionService.create(
                    claims,
                    request.pageKey(),
                    request.pageInstanceId(),
                    request.route(),
                    request.bridgeActions(),
                    request.sdkVersion());
            return ResponseEntity.ok(ApiResult.ok(new EmbedChatSessionResponse(
                    session.getSessionId(),
                    session.getAgentId(),
                    Map.of(
                            "tenantId", claims.getTenantId(),
                            "appId", claims.getAppId(),
                            "externalUserId", claims.getExternalUserId(),
                            "globalUserId", claims.getGlobalUserId()))));
        } catch (EmbedTokenException ex) {
            recordEmbedDeny(
                    "EMBED_SESSION",
                    "PAGE",
                    request == null ? null : request.pageInstanceId(),
                    ex.getMessage(),
                    sessionCreateMetadata(request));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.fail(401, ex.getMessage()));
        }
    }

    @PostMapping("/chat/sessions/{sessionId}/messages")
    public ResponseEntity<ApiResult<ChatResponse>> sendMessage(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody EmbedChatMessageRequest request) {
        try {
            return ResponseEntity.ok(ApiResult.ok(executeMessage(sessionId, authorization, request)));
        } catch (EmbedTokenException ex) {
            recordEmbedDeny(
                    "EMBED_CHAT",
                    "SESSION",
                    sessionId,
                    ex.getMessage(),
                    Map.of("sessionId", sessionId, "operation", "SEND_MESSAGE"));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.fail(401, embedChatErrorMessage(ex)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResult.fail(400, embedChatErrorMessage(ex)));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail(403, ex.getMessage()));
        }
    }

    @PostMapping(value = "/chat/sessions/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamMessage(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody EmbedChatMessageRequest request) {
        try {
            verifyBearer(authorization);
        } catch (EmbedTokenException ex) {
            recordEmbedDeny(
                    "EMBED_CHAT",
                    "SESSION",
                    sessionId,
                    ex.getMessage(),
                    Map.of("sessionId", sessionId, "operation", "STREAM_MESSAGE"));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        SseEmitter emitter = new SseEmitter();
        CompletableFuture.runAsync(() -> {
            try {
                ChatResponse response = executeMessage(sessionId, authorization, request);
                EmbedTokenClaims claims = verifyBearer(authorization);
                EmbedSessionEntity session = embedSessionService.requireActiveSession(sessionId, claims);
                for (EmbedChatStreamEvent event : EmbedChatStreamEventFactory.from(response)) {
                    embedAuditEventService.recordStreamEvent(session, event);
                    emitter.send(SseEmitter.event()
                            .name(event.type())
                            .data(event.data()));
                }
                emitter.complete();
            } catch (Exception ex) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", embedChatErrorMessage(ex))));
                } catch (IOException ignored) {
                    // client already disconnected
                }
                emitter.completeWithError(ex);
            }
        });
        return ResponseEntity.ok(emitter);
    }

    @PostMapping("/chat/sessions/{sessionId}/page-actions/{requestId}/result")
    public ResponseEntity<ApiResult<PageActionResultResponse>> pageActionResult(
            @PathVariable String sessionId,
            @PathVariable String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody PageActionResultRequest request) {
        try {
            EmbedTokenClaims claims = verifyBearer(authorization);
            EmbedSessionEntity session = embedSessionService.requireActiveSession(sessionId, claims);
            if (request == null || !requestId.equals(request.requestId())) {
                throw new IllegalArgumentException("page action result requestId does not match path");
            }
            embedAuditEventService.recordPageActionResult(session, requestId, request.toMap());
            return ResponseEntity.ok(ApiResult.ok(new PageActionResultResponse(requestId, request.status())));
        } catch (EmbedTokenException ex) {
            recordEmbedDeny(
                    "EMBED_PAGE_ACTION",
                    "SESSION",
                    sessionId,
                    ex.getMessage(),
                    Map.of("sessionId", sessionId, "requestId", requestId, "operation", "PAGE_ACTION_RESULT"));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.fail(401, ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResult.fail(400, ex.getMessage()));
        }
    }

    @GetMapping("/chat/sessions/{sessionId}/page-actions/pending")
    public ResponseEntity<ApiResult<List<PageActionDispatchResponse>>> pendingPageActions(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "10") Integer limit) {
        try {
            EmbedTokenClaims claims = verifyBearer(authorization);
            EmbedSessionEntity session = embedSessionService.requireActiveSession(sessionId, claims);
            List<PageActionDispatchResponse> actions = embedAuditEventService
                    .pendingPageActionRequests(session, limit == null ? 10 : limit)
                    .stream()
                    .map(this::toPageActionDispatch)
                    .toList();
            return ResponseEntity.ok(ApiResult.ok(actions));
        } catch (EmbedTokenException ex) {
            recordEmbedDeny(
                    "EMBED_PAGE_ACTION_PENDING",
                    "SESSION",
                    sessionId,
                    ex.getMessage(),
                    Map.of("sessionId", sessionId, "operation", "PAGE_ACTION_PENDING"));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.fail(401, ex.getMessage()));
        }
    }

    private PageActionDispatchResponse toPageActionDispatch(com.enterprise.ai.agent.platform.control.identity.PageActionEventEntity event) {
        Map<String, Object> args = readObjectMap(event.getArgsJson());
        Map<String, Object> target = new LinkedHashMap<>();
        if (StringUtils.hasText(event.getTargetPageInstanceId())) {
            target.put("pageInstanceId", event.getTargetPageInstanceId());
        }
        return new PageActionDispatchResponse(
                "page.action.requested",
                "1.0",
                event.getRequestId(),
                event.getActionKey(),
                event.getTitle(),
                args,
                target,
                Boolean.TRUE.equals(event.getConfirmRequired()));
    }

    private ChatResponse executeMessage(String sessionId, String authorization, EmbedChatMessageRequest request) {
        EmbedTokenClaims claims = verifyBearer(authorization);
        EmbedSessionEntity session = embedSessionService.requireActiveSession(sessionId, claims);
        embedAuditEventService.recordUserMessage(session, request.message());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tenantId", session.getTenantId());
        metadata.put("appId", session.getAppId());
        metadata.put("projectCode", session.getProjectCode());
        metadata.put("externalUserId", session.getExternalUserId());
        metadata.put("globalUserId", session.getGlobalUserId());
        metadata.put("pageInstanceId", session.getPageInstanceId());
        metadata.put("origin", session.getOrigin());
        metadata.put("route", session.getRoute());
        metadata.put("principal", claims.toPrincipal());
        metadata.put("runtimeContextQuery", request.message());

        Optional<EmbedWorkflowRuntimeService.RunnableWorkflowContext> workflowContext =
                embedWorkflowRuntimeService.resolveRunnableWorkflowContext(session, null);
        String intentType;
        AgentResult result;
        Map<String, Object> candidateMetadata = metadata;
        RuntimeMemoryCandidateIdentity candidateIdentity = RuntimeMemoryCandidateIdentity.empty();
        RuntimeContextInjectionResult runtimeContextForResponse;
        if (workflowContext.isPresent()) {
            EmbedWorkflowRuntimeService.RunnableWorkflowContext context = workflowContext.get();
            Map<String, Object> workflowMetadata = new HashMap<>(metadata);
            workflowMetadata.put("bindingId", context.binding().getId());
            workflowMetadata.put("bindingType", context.binding().getBindingType());
            workflowMetadata.put("pageKey", session.getPageKey());
            workflowMetadata.put("route", session.getRoute());
            candidateMetadata = workflowMetadata;
            candidateIdentity = RuntimeMemoryCandidateIdentity.fromAgentWorkflow(context.agent(), context.workflow());
            AgentRuntimeProfile profile = AgentRuntimeProfile.fromAgentEntry(context.agent(), objectMapper);
            workflowMetadata.put("projectId", profile.getProjectId());
            workflowMetadata.put("modelInstanceId", profile.getModelInstanceId());
            RuntimeContextInjectionResult runtimeContext = runtimeContextPackageService.injectForEmbedWorkflow(
                    session, claims, workflowMetadata, context.agent(), context.workflow(), profile);
            runtimeContextForResponse = runtimeContext;
            attachRuntimeContextMetadata(workflowMetadata, runtimeContext);
            result = workflowRuntimeService.execute(WorkflowRuntimeRequest.builder()
                    .sessionId(sessionId)
                    .message(request.message())
                    .agent(context.agent())
                    .workflow(context.workflow())
                    .activeVersion(context.activeVersion())
                    .principal(principalMap(claims))
                    .pageContext(pageContext(session))
                    .metadata(workflowMetadata)
                    .runtimeContext(runtimeContext)
                    .build());
            intentType = firstText(context.binding().getIntentType(), context.workflow().getWorkflowType());
        } else {
            AgentEntryEntity entry = embedWorkflowRuntimeService.resolveAgentEntry(session.getAgentId())
                    .orElseThrow(() -> new IllegalArgumentException("agent not found: " + session.getAgentId()));
            candidateIdentity = RuntimeMemoryCandidateIdentity.fromAgent(entry);
            AgentRuntimeProfile profile = AgentRuntimeProfile.fromAgentEntry(entry, objectMapper);
            metadata.put("projectId", profile.getProjectId());
            metadata.put("modelInstanceId", profile.getModelInstanceId());
            RuntimeContextInjectionResult runtimeContext = runtimeContextPackageService.injectForEmbedAgent(
                    session, claims, metadata, entry, profile);
            runtimeContextForResponse = runtimeContext;
            attachRuntimeContextMetadata(metadata, runtimeContext);
            result = agentRouter.executeByProfile(
                    profile,
                    sessionId,
                    claims.getExternalUserId(),
                    request.message(),
                    claims.getRoles(),
                    metadata,
                    runtimeContext);
            intentType = profile.getIntentType();
        }
        Map<String, Object> responseMetadata = result.getMetadata() == null
                ? new HashMap<>()
                : new HashMap<>(result.getMetadata());
        attachRuntimeContextMetadata(responseMetadata, runtimeContextForResponse);
        if (result.isSuccess()) {
            attachMemoryCandidateMetadata(responseMetadata,
                    runtimeMemoryCandidateService.tryCreateFromInteraction(
                            session, claims, candidateMetadata, request.message(),
                            result.getAnswer(),
                            stringValue(responseMetadata.get("traceId")),
                            candidateIdentity));
        }
        ChatResponse response = ChatResponse.builder()
                .sessionId(sessionId)
                .answer(result.getAnswer())
                .intentType(intentType)
                .toolCalls(result.getToolResults() == null ? List.of() : result.getToolResults().keySet().stream().toList())
                .metadata(responseMetadata)
                .uiRequest(result.getUiRequest())
                .build();
        ensurePageActionAllowed(session, response.getUiRequest());
        embedRendererAuthorizationService.ensureAllowed(session, response.getUiRequest());
        embedAuditEventService.recordAssistantResponse(session, response);
        return response;
    }

    private String embedChatErrorMessage(Exception ex) {
        String message = ex.getMessage();
        if (ACTIVE_WORKFLOW_VERSION_REQUIRED.equals(message)) {
            return WORKFLOW_NOT_PUBLISHED_MESSAGE;
        }
        if (EMBED_CHAT_SESSION_EXPIRED.equals(message) || EMBED_TOKEN_EXPIRED.equals(message)) {
            return EMBED_SECRET_EXPIRED_MESSAGE;
        }
        return message == null ? "stream failed" : message;
    }

    private Map<String, Object> principalMap(EmbedTokenClaims claims) {
        Map<String, Object> principal = new HashMap<>();
        principal.put("externalUserId", claims.getExternalUserId());
        principal.put("globalUserId", claims.getGlobalUserId());
        principal.put("userName", claims.getUserName());
        principal.put("roles", claims.getRoles() == null ? List.of() : claims.getRoles());
        principal.put("attributes", claims.getAttributes() == null ? Map.of() : claims.getAttributes());
        return principal;
    }

    private Map<String, Object> pageContext(EmbedSessionEntity session) {
        Map<String, Object> page = new HashMap<>();
        page.put("pageKey", session.getPageKey());
        page.put("pageInstanceId", session.getPageInstanceId());
        page.put("route", session.getRoute());
        page.put("origin", session.getOrigin());
        return page;
    }

    private void attachMemoryCandidateMetadata(Map<String, Object> metadata,
                                               RuntimeMemoryCandidateResult candidateResult) {
        if (metadata == null || candidateResult == null) {
            return;
        }
        metadata.put("memoryCandidateCreated", candidateResult.isCreated());
        if (candidateResult.getCandidateId() != null) {
            metadata.put("memoryCandidateId", candidateResult.getCandidateId());
        }
        if (candidateResult.getCandidateIds() != null && !candidateResult.getCandidateIds().isEmpty()) {
            metadata.put("memoryCandidateIds", candidateResult.getCandidateIds());
        }
        metadata.put("memoryCandidateCount", candidateResult.getCreatedCount());
        if (StringUtils.hasText(candidateResult.getExtractionMode())) {
            metadata.put("memoryCandidateExtractionMode", candidateResult.getExtractionMode());
        }
        if (StringUtils.hasText(candidateResult.getSkippedReason())) {
            metadata.put("memoryCandidateSkippedReason", candidateResult.getSkippedReason());
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void attachRuntimeContextMetadata(Map<String, Object> metadata,
                                              RuntimeContextInjectionResult runtimeContext) {
        if (metadata == null || runtimeContext == null) {
            return;
        }
        metadata.put("runtimeContextEnabled", runtimeContext.isEnabled());
        if (StringUtils.hasText(runtimeContext.getSkippedReason())) {
            metadata.put("runtimeContextSkippedReason", runtimeContext.getSkippedReason());
        }
        if (runtimeContext.isEnabled()) {
            metadata.put("runtimeContextItemCount", runtimeContext.getItemCount());
            metadata.put("runtimeContextTruncatedCount", runtimeContext.getTruncatedCount());
            List<Map<String, Object>> hits = runtimeContextHitSummaries(runtimeContext);
            if (!hits.isEmpty()) {
                metadata.put("runtimeContextHits", hits);
            }
        }
    }

    private List<Map<String, Object>> runtimeContextHitSummaries(RuntimeContextInjectionResult runtimeContext) {
        ContextPackageResponse pkg = runtimeContext.getPackageResponse();
        if (pkg == null) {
            return List.of();
        }
        List<Map<String, Object>> hits = new java.util.ArrayList<>();
        appendRuntimeContextHits(hits, "userMemory", pkg.getUserMemory());
        appendRuntimeContextHits(hits, "pageContext", pkg.getPageContext());
        appendRuntimeContextHits(hits, "workflowContext", pkg.getWorkflowContext());
        appendRuntimeContextHits(hits, "apiContext", pkg.getApiContext());
        appendRuntimeContextHits(hits, "rules", pkg.getRules());
        return hits.size() > 10 ? hits.subList(0, 10) : hits;
    }

    private void appendRuntimeContextHits(List<Map<String, Object>> target,
                                          String section,
                                          List<ContextSearchResult> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        for (ContextSearchResult hit : hits) {
            if (hit == null || hit.getItem() == null) {
                continue;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("section", section);
            summary.put("itemId", hit.getItem().getId());
            summary.put("itemType", hit.getItem().getItemType());
            summary.put("title", hit.getItem().getTitle());
            summary.put("rankScore", hit.getRankScore());
            summary.put("hitReason", hit.getHitReason());
            if (StringUtils.hasText(hit.getScoreBreakdown())) {
                summary.put("scoreBreakdown", hit.getScoreBreakdown());
            }
            target.add(summary);
        }
    }

    private Map<String, Object> readObjectMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private ResponseEntity<ApiResult<BusinessUserSyncResult>> markUser(String projectCode,
                                                                       String externalUserId,
                                                                       HttpServletRequest servletRequest,
                                                                       boolean disable) {
        try {
            RegistryCredentialEntity credential = registrySecurityService.verifyRequired(projectCode, signatureHeaders(servletRequest));
            BusinessUserSyncResult result = disable
                    ? businessUserDirectoryService.disableExternalUser("default", credential.getProjectCode(), externalUserId)
                    : businessUserDirectoryService.deleteExternalUser("default", credential.getProjectCode(), externalUserId);
            return ResponseEntity.ok(ApiResult.ok(result));
        } catch (IllegalArgumentException ex) {
            recordEmbedDeny(
                    "EMBED_USER_SYNC",
                    "USER",
                    externalUserId,
                    ex.getMessage(),
                    Map.of(
                            "projectCode", projectCode,
                            "externalUserId", externalUserId,
                            "operation", disable ? "DISABLE" : "DELETE"));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail(403, ex.getMessage()));
        }
    }

    private void recordEmbedDeny(String decisionType,
                                 String targetKind,
                                 String targetName,
                                 String reason,
                                 Map<String, ?> metadata) {
        guardDecisionLogService.record(
                null,
                decisionType,
                targetKind,
                StringUtils.hasText(targetName) ? targetName : "UNKNOWN",
                "DENY",
                reason,
                metadata == null ? Map.of() : metadata);
    }

    private Map<String, Object> tokenExchangeMetadata(EmbedTokenExchangeRequest request, HttpServletRequest servletRequest) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("operation", "TOKEN_EXCHANGE");
        if (request != null) {
            metadata.put("projectCode", request.projectCode());
            metadata.put("agentId", request.agentId());
            metadata.put("pageInstanceId", request.pageInstanceId());
            metadata.put("pageKey", request.pageKey());
            metadata.put("origin", request.origin());
            metadata.put("route", request.route());
            if (request.principal() != null) {
                metadata.put("externalUserId", request.principal().getExternalUserId());
                metadata.put("globalUserId", request.principal().getGlobalUserId());
            }
        }
        if (servletRequest != null) {
            metadata.put("remoteAddr", servletRequest.getRemoteAddr());
        }
        return metadata;
    }

    private Map<String, Object> sessionCreateMetadata(EmbedChatSessionCreateRequest request) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("operation", "CREATE_SESSION");
        if (request != null) {
            metadata.put("pageInstanceId", request.pageInstanceId());
            metadata.put("pageKey", request.pageKey());
            metadata.put("route", request.route());
            metadata.put("bridgeActions", request.bridgeActions());
            metadata.put("sdkVersion", request.sdkVersion());
        }
        return metadata;
    }

    private EmbedTokenClaims verifyBearer(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new EmbedTokenException("Authorization Bearer embed token is required");
        }
        return embedTokenService.verify(authorization.substring("Bearer ".length()).trim());
    }

    private RegistrySecurityService.RegistrySignatureHeaders signatureHeaders(HttpServletRequest request) {
        return new RegistrySecurityService.RegistrySignatureHeaders(
                firstHeader(request, "X-ReachAI-App-Key", "X-EAF-App-Key"),
                firstHeader(request, "X-ReachAI-Timestamp", "X-EAF-Timestamp"),
                firstHeader(request, "X-ReachAI-Nonce", "X-EAF-Nonce"),
                firstHeader(request, "X-ReachAI-Signature", "X-EAF-Signature"));
    }

    private String firstHeader(HttpServletRequest request, String primary, String fallback) {
        if (request == null) {
            return null;
        }
        String value = request.getHeader(primary);
        return StringUtils.hasText(value) ? value : request.getHeader(fallback);
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

    private void ensureAgentAllowed(RegistryCredentialEntity credential, String projectCode, String agentId) {
        List<String> allowed = readStringList(credential.getAllowedAgentIdsJson());
        if (!allowed.isEmpty() && allowed.stream().noneMatch(agentId::equals)) {
            throw new IllegalArgumentException("agent is not allowed by embed policy: " + agentId);
        }
        Optional<AgentEntryEntity> entry = embedWorkflowRuntimeService.resolveAgentEntry(agentId);
        if (entry != null && entry.isPresent()) {
            AgentEntryEntity agent = entry.get();
            if (Boolean.FALSE.equals(agent.getEnabled())) {
                throw new IllegalArgumentException("agent is disabled: " + agentId);
            }
            if (StringUtils.hasText(agent.getProjectCode()) && !projectCode.equals(agent.getProjectCode())) {
                throw new IllegalArgumentException("agent project does not match embed project");
            }
            return;
        }
        if (entry.isEmpty()) {
            throw new IllegalArgumentException("agent not found: " + agentId);
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void ensurePageActionAllowed(EmbedSessionEntity session, com.enterprise.ai.agent.model.interactive.UiRequestPayload uiRequest) {
        if (session == null || uiRequest == null || uiRequest.getExtension() == null) {
            return;
        }
        Object pageAction = uiRequest.getExtension().get("pageActionRequest");
        if (!(pageAction instanceof Map<?, ?> map)) {
            return;
        }
        String actionKey = String.valueOf(map.get("actionKey"));
        if (!StringUtils.hasText(actionKey)) {
            return;
        }
        List<String> allowed = readStringList(session.getBridgeActionsJson());
        if (allowed.stream().noneMatch(actionKey::equals)) {
            guardDecisionLogService.record(
                    null,
                    "EMBED_PAGE_ACTION",
                    "PAGE_ACTION",
                    actionKey,
                    "DENY",
                    "page action is not registered in current session",
                    Map.of(
                            "sessionId", session.getSessionId(),
                            "appId", session.getAppId(),
                            "agentId", session.getAgentId(),
                            "pageInstanceId", session.getPageInstanceId(),
                            "actionKey", actionKey));
            throw new IllegalStateException("page action is not registered in current session: " + actionKey);
        }
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

    public record EmbedTokenExchangeRequest(
            String projectCode,
            String agentId,
            String pageKey,
            String pageInstanceId,
            String route,
            String origin,
            BusinessPrincipal principal) {
    }

    public record EmbedTokenExchangeResponse(String token, long expiresIn, Map<String, String> sessionHint) {
    }

    public record BusinessUserBatchSyncRequest(List<BusinessUserSyncCommand> users) {
        public BusinessUserBatchSyncRequest {
            users = users == null ? List.of() : users;
        }
    }

    public record EmbedChatSessionCreateRequest(String pageKey, String pageInstanceId, String route, List<String> bridgeActions, String sdkVersion) {
    }

    public record EmbedChatSessionResponse(String sessionId, String agentId, Map<String, String> principal) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedChatMessageRequest(@JsonAlias({"content", "text"}) String message) {
    }

    public record PageActionResultRequest(
            String type,
            String protocolVersion,
            String requestId,
            String actionKey,
            String status,
            Object data,
            String error) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("type", type);
            map.put("protocolVersion", protocolVersion);
            map.put("requestId", requestId);
            map.put("actionKey", actionKey);
            map.put("status", status);
            map.put("data", data);
            map.put("error", error);
            return map;
        }
    }

    public record PageActionResultResponse(String requestId, String status) {
    }

    public record PageActionDispatchResponse(
            String type,
            String protocolVersion,
            String requestId,
            String actionKey,
            String title,
            Map<String, Object> args,
            Map<String, Object> target,
            boolean confirm) {
    }
}
