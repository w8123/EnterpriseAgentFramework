package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.knowledge.CapabilityProjectRequestVerificationGateway;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.runtime.RuntimeTrustedAgentExecutionGateway;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/api/embed")
@RequiredArgsConstructor
public class PlatformEmbedPublicController {

    static final String RUNTIME_FAILURE_MESSAGE = "助手未能完成请求，请先核对业务记录，再决定是否重试。";

    private final PlatformEmbedTokenService tokenService;
    private final PlatformEmbedSessionService sessionService;
    private final PlatformEmbedChatEventService chatEventService;
    private final CapabilityProxyClient capabilityProxyClient;
    private final RuntimeProxyClient runtimeProxyClient;
    private final RuntimeTrustedAgentExecutionGateway trustedExecutionGateway;
    private final PlatformEmbedStreamRelay embedStreamRelay;
    private final PlatformPageActionEventMapper pageActionEventMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private BusinessUserSyncService businessUserSyncService;
    private CapabilityProjectRequestVerificationGateway projectRequestVerifier;
    private PlatformEmbedBusinessSessionRevocation businessSessionRevocation;

    @Autowired
    void setProjectRequestVerifier(CapabilityProjectRequestVerificationGateway verifier) {
        this.projectRequestVerifier = verifier;
    }

    @Autowired
    void setBusinessSessionRevocation(PlatformEmbedBusinessSessionRevocation revocation) {
        this.businessSessionRevocation = revocation;
    }

    /** Optional so embed keeps working where the user directory is not wired (tests, slim setups). */
    @Autowired(required = false)
    void setBusinessUserSyncService(BusinessUserSyncService businessUserSyncService) {
        this.businessUserSyncService = businessUserSyncService;
    }

    @PostMapping("/token/exchange")
    public ResponseEntity<ApiResult<EmbedTokenExchangeResponse>> exchangeToken(
            @RequestHeader(value = "X-ReachAI-App-Key", required = false) String reachAiAppKey,
            @RequestHeader(value = "X-EAF-App-Key", required = false) String eafAppKey,
            @RequestHeader(value = "X-ReachAI-Timestamp", required = false) String reachAiTimestamp,
            @RequestHeader(value = "X-EAF-Timestamp", required = false) String eafTimestamp,
            @RequestHeader(value = "X-ReachAI-Nonce", required = false) String reachAiNonce,
            @RequestHeader(value = "X-EAF-Nonce", required = false) String eafNonce,
            @RequestHeader(value = "X-ReachAI-Signature", required = false) String reachAiSignature,
            @RequestHeader(value = "X-EAF-Signature", required = false) String eafSignature,
            @RequestHeader(value = "X-ReachAI-Body-SHA256", required = false) String bodySha256,
            @RequestHeader(value = "X-ReachAI-Body-Signature", required = false) String bodySignature,
            @RequestBody byte[] body) {
        StopWatch timing = new StopWatch();
        timing.start("validate");
        try {
            String digest = InternalServiceHmac.bodySha256Hex(body);
            if (!InternalServiceHmac.digestEqualsConstantTime(digest, bodySha256)) {
                throw new PlatformEmbedTokenException("embed request body signature is required");
            }
            EmbedTokenExchangeRequest request = objectMapper.readValue(body, EmbedTokenExchangeRequest.class);
            requireText(request == null ? null : request.projectCode(), "projectCode");
            requireText(request.agentId(), "agentId");
            requireText(request.pageInstanceId(), "pageInstanceId");
            requireText(request.origin(), "origin");
            BusinessPrincipal principal = request.principal();
            if (principal == null || !StringUtils.hasText(principal.externalUserId())) {
                throw new PlatformEmbedTokenException("principal.externalUserId is required");
            }
            if (projectRequestVerifier == null) {
                throw new PlatformEmbedTokenException("project request verification is unavailable");
            }
            timing.stop();
            timing.start("signature");
            projectRequestVerifier.verify(new CapabilityProjectRequestVerificationGateway.ProjectRequest(
                    request.projectCode(), firstText(reachAiAppKey, eafAppKey), "POST",
                    "/api/embed/token/exchange", firstText(reachAiTimestamp, eafTimestamp),
                    firstText(reachAiNonce, eafNonce), digest, bodySignature));
            timing.stop();
            timing.start("policy");
            Map<String, Object> verified = verifyWithCapability(
                    firstText(reachAiAppKey, eafAppKey),
                    firstText(reachAiTimestamp, eafTimestamp),
                    firstText(reachAiNonce, eafNonce),
                    firstText(reachAiSignature, eafSignature),
                    request);
            timing.stop();
            timing.start("agent");
            ensureRuntimeAgentAvailable(request.projectCode(), request.agentId());
            timing.stop();
            timing.start("issue");
            String tenantId = attestedTenantId(principal);
            PlatformEmbedTokenIssueResult token = tokenService.issue(PlatformEmbedTokenIssueCommand.builder()
                    .tenantId(tenantId)
                    .appId(request.projectCode())
                    .projectCode(request.projectCode())
                    .agentId(request.agentId())
                    .pageKey(request.pageKey())
                    .pageInstanceId(request.pageInstanceId())
                    .route(request.route())
                    .origin(request.origin())
                    .businessSessionId(request.businessSessionId())
                    .businessSessionExpiresAt(request.businessSessionExpiresAt())
                    .ttlSeconds(asInteger(verified.get("tokenTtlSeconds")))
                    .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal(
                            principal.externalUserId(),
                            firstText(principal.globalUserId(), principal.externalUserId()),
                            principal.userName(),
                            principal.roles() == null ? List.of() : principal.roles(),
                            principal.attributes() == null ? Map.of() : principal.attributes()))
                    .build());
            timing.stop();
            timing.start("identity_sync");
            syncBusinessUser(tenantId, request.projectCode(), principal);
            Map<String, String> sessionHint = new LinkedHashMap<>();
            sessionHint.put("appId", request.projectCode());
            sessionHint.put("agentId", request.agentId());
            sessionHint.put("pageKey", StringUtils.hasText(request.pageKey()) ? request.pageKey() : "");
            sessionHint.put("pageInstanceId", request.pageInstanceId());
            return ResponseEntity.ok(ApiResult.ok(new EmbedTokenExchangeResponse(
                    token.token(),
                    token.expiresIn(),
                    sessionHint,
                    request.businessSessionId() != null,
                    request.businessSessionExpiresAt() != null)));
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail(403, "embed access denied"));
            }
            if (ex.getStatusCode().is5xxServerError()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResult.fail(503, "embed token service is temporarily unavailable"));
            }
            throw ex;
        } catch (PlatformEmbedTokenException | IllegalArgumentException | java.io.IOException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResult.fail(403, ex.getMessage()));
        } finally {
            if (timing.isRunning()) timing.stop();
            if (timing.getTotalTimeMillis() >= 5_000) {
                var stages = new java.util.StringJoiner(",");
                for (StopWatch.TaskInfo stage : timing.getTaskInfo()) {
                    stages.add(stage.getTaskName() + "=" + stage.getTimeMillis());
                }
                log.warn("Embed token exchange slow: totalMs={} stagesMs={}", timing.getTotalTimeMillis(), stages);
            }
        }
    }

    /** Called by the authenticated host backend when its login session ends. */
    @PostMapping("/token/business-session/revoke")
    public ResponseEntity<ApiResult<Void>> revokeBusinessSession(
            @RequestHeader(value = "X-ReachAI-App-Key", required = false) String appKey,
            @RequestHeader(value = "X-ReachAI-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-ReachAI-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-ReachAI-Body-SHA256", required = false) String bodySha256,
            @RequestHeader(value = "X-ReachAI-Body-Signature", required = false) String bodySignature,
            @RequestBody byte[] body) {
        try {
            String digest = InternalServiceHmac.bodySha256Hex(body);
            if (!InternalServiceHmac.digestEqualsConstantTime(digest, bodySha256)) {
                throw new PlatformEmbedTokenException("embed request body signature is required");
            }
            BusinessSessionRevokeRequest request = objectMapper.readValue(body, BusinessSessionRevokeRequest.class);
            requireText(request == null ? null : request.projectCode(), "projectCode");
            if (projectRequestVerifier == null || businessSessionRevocation == null) {
                throw new PlatformEmbedTokenException("embed business session revocation is unavailable");
            }
            projectRequestVerifier.verify(new CapabilityProjectRequestVerificationGateway.ProjectRequest(
                    request.projectCode(), appKey, "POST", "/api/embed/token/business-session/revoke",
                    timestamp, nonce, digest, bodySignature));
            businessSessionRevocation.revoke(request.projectCode(), request.businessSessionId());
            return ResponseEntity.ok(ApiResult.ok(null));
        } catch (PlatformEmbedTokenException | IllegalArgumentException | java.io.IOException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail(403, ex.getMessage()));
        }
    }

    /**
     * The business system just attested this user, so keep the ReachAI user directory current
     * (created when first seen, roles refreshed). Best effort: embed chat must not fail on it.
     */
    private String attestedTenantId(BusinessPrincipal principal) {
        Object tenant = principal.attributes() == null ? null : principal.attributes().get("tenantId");
        if (tenant == null) {
            return "default";
        }
        if (!(tenant instanceof String) || !StringUtils.hasText((String) tenant)) {
            throw new IllegalArgumentException("principal.attributes.tenantId must be a non-empty string");
        }
        return ((String) tenant).trim();
    }

    private void syncBusinessUser(String tenantId, String projectCode, BusinessPrincipal principal) {
        if (businessUserSyncService == null) {
            return;
        }
        try {
            businessUserSyncService.sync(new BusinessUserSyncService.AttestedBusinessUser(
                    tenantId, projectCode, principal.externalUserId(), principal.globalUserId(),
                    principal.userName(), principal.roles()));
        } catch (RuntimeException syncFailure) {
            log.warn("business user sync skipped for project {}: {}", projectCode, syncFailure.getMessage());
        }
    }

    @PostMapping("/chat/sessions")
    public ResponseEntity<ApiResult<EmbedChatSessionResponse>> createSession(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody EmbedChatSessionCreateRequest request) {
        try {
            PlatformEmbedTokenClaims claims = verifyBearer(authorization);
            PlatformEmbedSessionEntity session = sessionService.create(
                    claims,
                    request == null ? null : request.pageKey(),
                    request == null ? null : request.pageInstanceId(),
                    request == null ? null : request.route(),
                    request == null ? List.of() : request.bridgeActions(),
                    request == null ? null : request.sdkVersion());
            Map<String, Object> principal = Map.of(
                    "tenantId", claims.getTenantId(),
                    "appId", claims.getAppId(),
                    "externalUserId", claims.getExternalUserId(),
                    "globalUserId", claims.getGlobalUserId());
            return ResponseEntity.ok(ApiResult.ok(new EmbedChatSessionResponse(
                    session.getSessionId(),
                    session.getAgentId(),
                    principal)));
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, ex.getMessage()));
        }
    }

    @PostMapping("/chat/sessions/{sessionId}/messages")
    public ResponseEntity<ApiResult<EmbedChatMessageResponse>> sendMessage(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody EmbedChatMessageRequest request) {
        try {
            return ResponseEntity.ok(ApiResult.ok(executeEmbedMessage(sessionId, authorization, request)));
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, ex.getMessage()));
        } catch (EmbedRuntimeFailure ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResult.fail(503, safeRuntimeFailureMessage(ex.code)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResult.fail(400, ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail(403, ex.getMessage()));
        }
    }

    /** Clears Runtime context only; the authenticated Embed session and audit events remain. */
    @PostMapping("/chat/sessions/{sessionId}/context/clear")
    public ResponseEntity<Void> clearSessionContext(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            PlatformEmbedTokenClaims claims = verifyBearer(authorization);
            PlatformEmbedSessionEntity session = sessionService.requireActiveSession(sessionId, claims);
            String user = firstText(session.getExternalUserId(), session.getGlobalUserId());
            if (!StringUtils.hasText(user) || trustedExecutionGateway == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            var owner = com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal.business(
                    session.getTenantId(), user, session.getProjectCode());
            return trustedExecutionGateway.clearTrusted(session.getSessionId(), "EMBED_SESSION",
                    firstText(session.getTenantId(), "default"), user.trim(), owner);
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @PostMapping(value = "/chat/sessions/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamMessage(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody EmbedChatMessageRequest request) {
        try {
            long controlStarted = System.nanoTime();
            PlatformEmbedTokenClaims claims = verifyBearer(authorization);
            long sessionLookupStart = System.nanoTime();
            PlatformEmbedSessionEntity session = sessionService.requireActiveSession(sessionId, claims);
            long sessionLookupMs = Math.max(0L, (System.nanoTime() - sessionLookupStart) / 1_000_000L);
            String message = request == null ? null : request.message();
            requireText(message, "message");
            long userAuditStart = System.nanoTime();
            chatEventService.recordUserMessage(session, message);
            long userMessageAuditMs = Math.max(0L, (System.nanoTime() - userAuditStart) / 1_000_000L);
            Map<String, Object> runtimeBody = runtimeContext(session, claims);
            runtimeBody.put("message", message);
            runtimeBody.put("intentHint", "EMBED_CHAT");
            runtimeBody.put("entryType", "EMBED");
            Map<String, Object> controlTiming = new LinkedHashMap<>();
            controlTiming.put("control.sessionLookupMs", sessionLookupMs);
            controlTiming.put("control.userMessageAuditMs", userMessageAuditMs);
            controlTiming.put("control.preRuntimeMs", Math.max(0L, (System.nanoTime() - controlStarted) / 1_000_000L));
            runtimeBody.put("controlTiming", controlTiming);
            StreamingResponseBody stream = outputStream ->
                    embedStreamRelay.streamMessage(session, runtimeBody, outputStream);
            return streamResponse(stream);
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @PostMapping(value = "/chat/sessions/{sessionId}/interactions/{interactionId}/submit/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamSubmitInteraction(
            @PathVariable String sessionId,
            @PathVariable String interactionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody EmbedInteractionSubmitRequest request) {
        try {
            PlatformEmbedTokenClaims claims = verifyBearer(authorization);
            PlatformEmbedSessionEntity session = sessionService.requireActiveSession(sessionId, claims);
            Map<String, Object> runtimeBody = runtimeContext(session, claims);
            runtimeBody.put("interactionId", requiredRequestText(interactionId, "interactionId"));
            runtimeBody.put("uiSubmit", Map.of(
                    "action", firstText(request == null ? null : request.action(), "confirm"),
                    "values", request == null || request.values() == null ? Map.of() : request.values()));
            if (request != null && StringUtils.hasText(request.idempotencyKey())) {
                runtimeBody.put("idempotencyKey", request.idempotencyKey().trim());
            }
            runtimeBody.put("intentHint", "EMBED_INTERACTION_RESUME");
            runtimeBody.put("entryType", "EMBED");
            StreamingResponseBody stream = outputStream ->
                    embedStreamRelay.streamMessage(session, runtimeBody, outputStream);
            return streamResponse(stream);
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @PostMapping("/chat/sessions/{sessionId}/interactions/{interactionId}/submit")
    public ResponseEntity<ApiResult<EmbedChatMessageResponse>> submitInteraction(
            @PathVariable String sessionId,
            @PathVariable String interactionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody EmbedInteractionSubmitRequest request) {
        try {
            PlatformEmbedTokenClaims claims = verifyBearer(authorization);
            PlatformEmbedSessionEntity session = sessionService.requireActiveSession(sessionId, claims);
            Map<String, Object> runtimeBody = runtimeContext(session, claims);
            runtimeBody.put("interactionId", requiredRequestText(interactionId, "interactionId"));
            runtimeBody.put("uiSubmit", Map.of(
                    "action", firstText(request == null ? null : request.action(), "confirm"),
                    "values", request == null || request.values() == null ? Map.of() : request.values()));
            if (request != null && StringUtils.hasText(request.idempotencyKey())) {
                runtimeBody.put("idempotencyKey", request.idempotencyKey().trim());
            }
            runtimeBody.put("intentHint", "EMBED_INTERACTION_RESUME");
            return ResponseEntity.ok(ApiResult.ok(executeRuntimeMessage(session, runtimeBody)));
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, ex.getMessage()));
        } catch (EmbedInteractionConflict ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResult.fail(409, "确认请求无效或已结束，请刷新对话查看结果"));
        } catch (EmbedRuntimeFailure ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResult.fail(503, safeRuntimeFailureMessage(ex.code)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResult.fail(400, ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail(403, ex.getMessage()));
        }
    }

    @GetMapping("/chat/sessions/{sessionId}/page-actions/pending")
    public ResponseEntity<ApiResult<List<PageActionDispatchRequest>>> listPendingPageActions(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            PlatformEmbedTokenClaims claims = verifyBearer(authorization);
            PlatformEmbedSessionEntity session = sessionService.requireActiveSession(sessionId, claims);
            List<PageActionDispatchRequest> requests = pageActionEventMapper.selectList(
                            new LambdaQueryWrapper<PlatformPageActionEventEntity>()
                                    .eq(PlatformPageActionEventEntity::getSessionId, sessionId)
                                    .eq(PlatformPageActionEventEntity::getStatus, "REQUESTED")
                                    .orderByAsc(PlatformPageActionEventEntity::getId)
                                    .last("limit " + safeLimit(limit, 10)))
                    .stream()
                    .filter(event -> isOwnedPageAction(session, event))
                    .map(event -> toPageActionDispatch(session, event))
                    .toList();
            return ResponseEntity.ok(ApiResult.ok(requests));
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, ex.getMessage()));
        }
    }

    @PostMapping("/chat/sessions/{sessionId}/page-actions/{requestId}/result")
    public ResponseEntity<ApiResult<PageActionResultResponse>> submitPageActionResult(
            @PathVariable String sessionId,
            @PathVariable String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody PageActionResultRequest request) {
        try {
            PlatformEmbedTokenClaims claims = verifyBearer(authorization);
            PlatformEmbedSessionEntity session = sessionService.requireActiveSession(sessionId, claims);
            String normalizedRequestId = requiredRequestText(requestId, "requestId");
            if (request != null
                    && StringUtils.hasText(request.requestId())
                    && !normalizedRequestId.equals(request.requestId().trim())) {
                throw new IllegalArgumentException("page action result requestId does not match path");
            }
            PlatformPageActionEventEntity event = findPageActionEvent(session, normalizedRequestId);
            if (event == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResult.fail(404, "page action request not found: " + normalizedRequestId));
            }
            if (request != null
                    && StringUtils.hasText(request.actionKey())
                    && StringUtils.hasText(event.getActionKey())
                    && !event.getActionKey().equals(request.actionKey().trim())) {
                throw new IllegalArgumentException("page action result actionKey does not match request");
            }
            String status = normalizePageActionStatus(request == null ? null : request.status());
            String currentStatus = normalizeEventStatus(event.getStatus());
            if (status.equals(currentStatus) && isTerminalPageActionStatus(currentStatus)) {
                return ResponseEntity.ok(ApiResult.ok(new PageActionResultResponse(
                        event.getRequestId(), event.getActionKey(), currentStatus, event.getErrorMessage())));
            }
            boolean canComplete = "EXECUTING".equals(currentStatus)
                    || ("REQUESTED".equals(currentStatus) && canCompleteWithoutExecution(status));
            if (!canComplete) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResult.fail(409,
                                "page action request is no longer active: " + normalizedRequestId
                                        + " (status=" + currentStatus + ")"));
            }
            String resultJson = toJson(request == null
                    ? Map.of("requestId", normalizedRequestId, "status", status)
                    : request);
            String errorMessage = text(request == null ? null : request.error());
            int updated = pageActionEventMapper.update(
                    null,
                    new UpdateWrapper<PlatformPageActionEventEntity>()
                            .eq("id", event.getId())
                            .eq("status", currentStatus)
                            .set("status", status)
                            .set("result_json", resultJson)
                            .set("error_message", errorMessage)
                            .set("completed_at", LocalDateTime.now()));
            if (updated != 1) {
                PlatformPageActionEventEntity latest = findPageActionEvent(session, normalizedRequestId);
                if (latest != null && status.equals(normalizeEventStatus(latest.getStatus()))) {
                    return ResponseEntity.ok(ApiResult.ok(new PageActionResultResponse(
                            latest.getRequestId(), latest.getActionKey(), latest.getStatus(), latest.getErrorMessage())));
                }
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResult.fail(409,
                                "page action request changed before result submission: " + normalizedRequestId));
            }
            return ResponseEntity.ok(ApiResult.ok(new PageActionResultResponse(
                    event.getRequestId(),
                    event.getActionKey(),
                    status,
                    errorMessage)));
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResult.fail(400, ex.getMessage()));
        }
    }

    @PostMapping("/chat/sessions/{sessionId}/page-actions/{requestId}/claim")
    public ResponseEntity<ApiResult<PageActionClaimResponse>> claimPageAction(
            @PathVariable String sessionId,
            @PathVariable String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) PageActionClaimRequest request) {
        try {
            PlatformEmbedTokenClaims claims = verifyBearer(authorization);
            PlatformEmbedSessionEntity session = sessionService.requireActiveSession(sessionId, claims);
            String normalizedRequestId = requiredRequestText(requestId, "requestId");
            if (request != null && StringUtils.hasText(request.requestId())
                    && !normalizedRequestId.equals(request.requestId().trim())) {
                throw new IllegalArgumentException("page action claim requestId does not match path");
            }
            PlatformPageActionEventEntity event = findPageActionEvent(session, normalizedRequestId);
            if (event == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResult.fail(404, "page action request not found: " + normalizedRequestId));
            }
            if (request != null && StringUtils.hasText(request.actionKey())
                    && StringUtils.hasText(event.getActionKey())
                    && !event.getActionKey().equals(request.actionKey().trim())) {
                throw new IllegalArgumentException("page action claim actionKey does not match request");
            }
            int updated = pageActionEventMapper.update(
                    null,
                    new UpdateWrapper<PlatformPageActionEventEntity>()
                            .eq("id", event.getId())
                            .eq("status", "REQUESTED")
                            .set("status", "EXECUTING"));
            if (updated != 1) {
                PlatformPageActionEventEntity latest = findPageActionEvent(session, normalizedRequestId);
                String status = latest == null ? "UNKNOWN" : normalizeEventStatus(latest.getStatus());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResult.fail(409,
                                "page action request cannot be claimed: " + normalizedRequestId
                                        + " (status=" + status + ")"));
            }
            return ResponseEntity.ok(ApiResult.ok(new PageActionClaimResponse(
                    event.getRequestId(), event.getActionKey(), "EXECUTING", true)));
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResult.fail(400, ex.getMessage()));
        }
    }

    /**
     * The target SPA page calls this after a platform-issued NAVIGATE action
     * completed locally and its new Page Bridge is ready. This is the only
     * public path allowed to move an existing Embed session to a different
     * page instance; ordinary session calls remain strictly page-bound.
     */
    @org.springframework.transaction.annotation.Transactional
    @PostMapping({"/chat/sessions/{sessionId}/page-bridge/rebind",
            "/chat/sessions/{sessionId}/page-bridge/rebind-v2"})
    public ResponseEntity<ApiResult<PageBridgeRebindResponse>> rebindPageBridge(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody PageBridgeRebindRequest request) {
        try {
            PlatformEmbedTokenClaims claims = verifyBearer(authorization);
            String normalizedSessionId = requiredRequestText(sessionId, "sessionId");
            String navigationRequestId = requiredRequestText(
                    request == null ? null : request.navigationRequestId(), "navigationRequestId");
            PlatformEmbedSessionEntity session = sessionService.requireActiveSessionForNavigationRebind(
                    normalizedSessionId, claims);
            requireTargetBindingMatchesToken(request, claims);
            PlatformPageActionEventEntity navigation = pageActionEventMapper.selectOne(
                    new LambdaQueryWrapper<PlatformPageActionEventEntity>()
                            .eq(PlatformPageActionEventEntity::getSessionId, normalizedSessionId)
                            .eq(PlatformPageActionEventEntity::getRequestId, navigationRequestId)
                            .eq(PlatformPageActionEventEntity::getCommandType, "NAVIGATE")
                            .eq(PlatformPageActionEventEntity::getActionKey,
                                    PlatformPageBridgeCommandService.NAVIGATE_ACTION)
                            .last("limit 1 for update"));
            if (!isOwnedPageAction(session, navigation) || !navigationRequestId.equals(navigation.getRequestId())
                    || !"NAVIGATE".equals(navigation.getCommandType())
                    || !PlatformPageBridgeCommandService.NAVIGATE_ACTION.equals(navigation.getActionKey())) {
                throw new IllegalArgumentException("platform navigation was not found: " + navigationRequestId);
            }
            // The source page claims the navigation only after any required
            // confirmation. A merely requested navigation cannot rebind a session.
            if (!"EXECUTING".equals(navigation.getStatus()) && !"SUCCESS".equals(navigation.getStatus())) {
                throw new IllegalArgumentException("platform navigation is no longer active: " + navigationRequestId);
            }
            if (!sameText(navigation.getTargetPageKey(), request.pageKey())
                    || !routeMatches(navigation.getTargetRoute(), request.route())) {
                throw new IllegalArgumentException("target page does not match pending platform navigation");
            }
            Map<String, Object> previousData = mapValue(parseJsonObject(navigation.getResultJson()).get("data"));
            if (Boolean.TRUE.equals(previousData.get("ready"))) {
                if (!sameText(text(previousData.get("pageInstanceId")), request.pageInstanceId())
                        || !sameText(session.getPageInstanceId(), request.pageInstanceId())
                        || !sameText(session.getPageKey(), request.pageKey())
                        || !sameText(session.getRoute(), request.route())) {
                    throw new IllegalArgumentException("completed navigation does not match the current page");
                }
                return ResponseEntity.ok(ApiResult.ok(new PageBridgeRebindResponse(
                        session.getSessionId(), session.getPageKey(), session.getPageInstanceId(), session.getRoute())));
            }
            if (!StringUtils.hasText(navigation.getTargetPageInstanceId())
                    || !navigation.getTargetPageInstanceId().equals(session.getPageInstanceId())) {
                throw new IllegalArgumentException("navigation source page is no longer active");
            }
            // Serialize before either database write: a validation/serialization response must
            // not commit a partially rebound session when this controller returns HTTP 400.
            String navigationResult = toJson(Map.of(
                    "protocolVersion", "1.0", "type", "page.action.result",
                    "requestId", navigation.getRequestId(), "actionKey", navigation.getActionKey(),
                    "status", "SUCCESS", "data", Map.of(
                            "pageKey", request.pageKey().trim(),
                            "pageInstanceId", request.pageInstanceId().trim(),
                            "route", request.route().trim(), "ready", true)));
            sessionService.rebindBridgeAfterNavigation(
                    session,
                    claims,
                    request.pageKey(),
                    request.pageInstanceId(),
                    request.route(),
                    request.bridgeActions(),
                    request.sdkVersion());
            navigation.setStatus("SUCCESS");
            navigation.setResultJson(navigationResult);
            navigation.setErrorMessage(null);
            navigation.setCompletedAt(LocalDateTime.now());
            if (pageActionEventMapper.updateById(navigation) != 1) {
                throw new IllegalStateException("navigation event changed during page bridge rebind");
            }
            return ResponseEntity.ok(ApiResult.ok(new PageBridgeRebindResponse(
                    session.getSessionId(),
                    session.getPageKey(),
                    session.getPageInstanceId(),
                    session.getRoute())));
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResult.fail(400, ex.getMessage()));
        }
    }

    private EmbedChatMessageResponse executeEmbedMessage(String sessionId,
                                                         String authorization,
                                                         EmbedChatMessageRequest request) {
        PlatformEmbedTokenClaims claims = verifyBearer(authorization);
        PlatformEmbedSessionEntity session = sessionService.requireActiveSession(sessionId, claims);
        String message = request == null ? null : request.message();
        requireText(message, "message");
        chatEventService.recordUserMessage(session, message);
        Map<String, Object> runtimeBody = runtimeContext(session, claims);
        runtimeBody.put("message", message);
        runtimeBody.put("intentHint", "EMBED_CHAT");
        runtimeBody.put("entryType", "EMBED");
        return executeRuntimeMessage(session, runtimeBody);
    }

    private Map<String, Object> runtimeContext(PlatformEmbedSessionEntity session,
                                               PlatformEmbedTokenClaims claims) {
        // Ownership is derived only from verified token/session claims — never from request body.
        Map<String, Object> runtimeBody = new LinkedHashMap<>();
        runtimeBody.put("agentId", session.getAgentId());
        runtimeBody.put("sessionId", session.getSessionId());
        runtimeBody.put("userId", claims.getExternalUserId());
        runtimeBody.put("projectCode", session.getProjectCode());
        putIfText(runtimeBody, "appId", firstText(session.getAppId(), claims.getAppId(), session.getProjectCode()));
        putIfText(runtimeBody, "tenantId", firstText(session.getTenantId(), claims.getTenantId()));
        putIfText(runtimeBody, "externalUserId", firstText(session.getExternalUserId(), claims.getExternalUserId()));
        putIfText(runtimeBody, "globalUserId", session.getGlobalUserId());
        putIfText(runtimeBody, "userName", claims.getUserName());
        Map<String, Object> attributes = claims.getAttributes() == null ? Map.of() : claims.getAttributes();
        runtimeBody.put("attributes", attributes);
        putIfText(runtimeBody, "deptId", text(attributes.get("deptId")));
        putIfText(runtimeBody, "deptName", text(attributes.get("deptName")));
        runtimeBody.put("pageKey", session.getPageKey());
        runtimeBody.put("route", session.getRoute());
        runtimeBody.put("roles", claims.getRoles() == null ? List.of() : claims.getRoles());
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfText(metadata, "tenantId", firstText(session.getTenantId(), claims.getTenantId()));
        putIfText(metadata, "appId", firstText(session.getAppId(), claims.getAppId(), session.getProjectCode()));
        putIfText(metadata, "projectCode", session.getProjectCode());
        putIfText(metadata, "externalUserId", firstText(session.getExternalUserId(), claims.getExternalUserId()));
        putIfText(metadata, "globalUserId", session.getGlobalUserId());
        putIfText(metadata, "userName", claims.getUserName());
        putIfText(metadata, "deptId", text(attributes.get("deptId")));
        putIfText(metadata, "deptName", text(attributes.get("deptName")));
        putIfText(metadata, "pageInstanceId", session.getPageInstanceId());
        putIfText(metadata, "origin", session.getOrigin());
        metadata.put("entryType", "EMBED");
        putIfText(metadata, "route", session.getRoute());
        runtimeBody.put("metadata", metadata);
        return runtimeBody;
    }

    private EmbedChatMessageResponse executeRuntimeMessage(PlatformEmbedSessionEntity session,
                                                            Map<String, Object> runtimeBody) {
        // Identity comes only from verified Embed token/session claims — never from chat body.
        String trustedUserId = firstText(session.getExternalUserId(), session.getGlobalUserId());
        ResponseEntity<Map<String, Object>> runtimeResponse;
        if (StringUtils.hasText(trustedUserId) && trustedExecutionGateway != null) {
            runtimeResponse = trustedExecutionGateway.executeTrusted(
                    runtimeBody, "EMBED_SESSION", firstText(session.getTenantId(), "default"), trustedUserId.trim(),
                    com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal.business(
                            session.getTenantId(), trustedUserId, session.getProjectCode()));
        } else {
            // Fail closed: without verified user, use public execute (userTrusted=false).
            runtimeResponse = runtimeProxyClient.executeAgent(runtimeBody);
        }
        if (runtimeResponse.getStatusCode().value() == 403) {
            throw new IllegalStateException("interaction does not belong to the current session/user");
        }
        if (runtimeResponse.getStatusCode().value() == 409) {
            throw new IllegalArgumentException("interaction submit conflict");
        }
        if (runtimeResponse.getStatusCode().value() == 410) {
            throw new IllegalArgumentException("interaction session expired");
        }
        if (!runtimeResponse.getStatusCode().is2xxSuccessful() || runtimeResponse.getBody() == null) {
            Map<String, Object> err = runtimeResponse.getBody() == null ? Map.of() : runtimeResponse.getBody();
            String code = text(err.get("code"));
            if (!StringUtils.hasText(code) && err.get("metadata") instanceof Map<?, ?> meta) {
                code = text(meta.get("code"));
            }
            throw new IllegalStateException(StringUtils.hasText(code)
                    ? ("runtime agent execution failed: " + code)
                    : "runtime agent execution failed");
        }
        Map<String, Object> body = runtimeResponse.getBody();
        // Runtime communicates execution failures in a successful HTTP response as success=false.
        // Do not present its diagnostic answer as an assistant reply or record it as a successful turn.
        if (Boolean.FALSE.equals(body.get("success")) && !isWaitingInteraction(body)) {
            if (StringUtils.hasText(text(runtimeBody.get("interactionId")))
                    && "SUPERVISOR_APPROVAL_INVALID".equals(text(mapValue(body.get("metadata")).get("code")))) {
                throw new EmbedInteractionConflict();
            }
            throw new EmbedRuntimeFailure(text(mapValue(body.get("metadata")).get("code")));
        }
        Map<String, Object> metadata = mapValue(body.get("metadata"));
        String answer = text(body.get("answer"));
        chatEventService.recordAssistantMessage(session, answer, body, text(metadata.get("traceId")));
        return new EmbedChatMessageResponse(
                firstText(text(body.get("sessionId")), session.getSessionId()),
                answer,
                text(body.get("intentType")),
                stringList(body.get("toolCalls")),
                metadata,
                body.get("uiRequest"));
    }

    static String safeRuntimeFailureMessage(String code) {
        if ("HTTP_API_WORKFLOW_RESULT_UNCONFIRMED".equals(code) || "PAGE_BRIDGE_RESULT_UNCONFIRMED".equals(code)) {
            return "操作结果尚未确认，请先核对业务记录，避免重复提交。";
        }
        if ("SUPERVISOR_PLAN_INCOMPLETE".equals(code)) {
            return "请求未能全部完成，请先核对已执行的操作，再调整条件重试。";
        }
        if ("SUPERVISOR_PLAN_REQUIRED".equals(code)) {
            return "未能完成请求规划，请检查输入条件后重试。";
        }
        return RUNTIME_FAILURE_MESSAGE;
    }

    private static final class EmbedRuntimeFailure extends RuntimeException {
        private final String code;
        private EmbedRuntimeFailure(String code) { this.code = code; }
    }

    private static final class EmbedInteractionConflict extends RuntimeException {
    }

    private boolean isWaitingInteraction(Map<String, Object> body) {
        Map<String, Object> metadata = mapValue(body.get("metadata"));
        String code = text(metadata.get("code"));
        if (!"SUPERVISOR_CONFIRMATION_REQUIRED".equals(code)
                && !"RUNTIME_GRAPH_INTERACTION_WAITING".equals(code)) return false;
        Map<String, Object> request = mapValue(body.get("uiRequest"));
        return StringUtils.hasText(text(request.get("interactionId")));
    }

    private PageActionDispatchRequest toPageActionDispatch(PlatformEmbedSessionEntity session,
                                                           PlatformPageActionEventEntity event) {
        Map<String, Object> target = new LinkedHashMap<>();
        putIfText(target, "pageInstanceId", firstText(event.getTargetPageInstanceId(), session.getPageInstanceId()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfText(metadata, "tenantId", session.getTenantId());
        putIfText(metadata, "appId", session.getAppId());
        putIfText(metadata, "projectCode", session.getProjectCode());
        putIfText(metadata, "agentId", session.getAgentId());
        putIfText(metadata, "commandType", event.getCommandType());
        putIfText(metadata, "parentRequestId", event.getParentRequestId());
        putIfText(metadata, "pageKey", firstText(event.getTargetPageKey(), session.getPageKey()));
        putIfText(metadata, "route", firstText(event.getTargetRoute(), session.getRoute()));
        return new PageActionDispatchRequest(
                "page.action.requested",
                "1.0",
                event.getRequestId(),
                event.getActionKey(),
                event.getTitle(),
                event.getNodeId(),
                target,
                Boolean.TRUE.equals(event.getConfirmRequired()),
                parseJsonObject(event.getArgsJson()),
                metadata);
    }

    private PlatformEmbedTokenClaims verifyBearer(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new PlatformEmbedTokenException("Authorization Bearer embed token is required");
        }
        return tokenService.verify(authorization.substring("Bearer ".length()).trim());
    }

    private Map<String, Object> verifyWithCapability(String appKey,
                                                     String timestamp,
                                                     String nonce,
                                                     String signature,
                                                     EmbedTokenExchangeRequest request) {
        ResponseEntity<Map<String, Object>> response;
        try {
            response = capabilityProxyClient.verifyEmbedTokenExchange(
                    appKey,
                    timestamp,
                    nonce,
                    signature,
                    Map.of(
                            "projectCode", request.projectCode(),
                            "agentId", request.agentId(),
                            "origin", request.origin()));
        } catch (FeignException ex) {
            if (ex.status() == 403) {
                throw new PlatformEmbedTokenException("embed token exchange is not allowed");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "embed policy service is unavailable");
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "embed policy service is unavailable");
        }
        if (response != null && response.getStatusCode().value() == 403) {
            throw new PlatformEmbedTokenException("embed token exchange is not allowed");
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "embed policy service is unavailable");
        }
        return response.getBody();
    }

    private void ensureRuntimeAgentAvailable(String projectCode, String agentId) {
        // Older Runtime versions ignore the view parameter and return the full list, which
        // remains compatible with the same project and enabled checks below.
        ResponseEntity<Object> response;
        try {
            response = runtimeProxyClient.listEmbedAgentIdentities(projectCode, "embed-identity");
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "agent service is unavailable");
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "agent service is unavailable");
        }
        if (PlatformEmbedAgentLookup.find(response.getBody(), projectCode, agentId).isEmpty()) {
            throw new PlatformEmbedTokenException("agent not found: " + agentId);
        }
    }

    private int safeLimit(int requested, int fallback) {
        int value = requested <= 0 ? fallback : requested;
        return Math.min(Math.max(value, 1), 1000);
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, val) -> result.put(String.valueOf(key), val));
        return result;
    }

    private Map<String, Object> parseJsonObject(String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(value, new TypeReference<>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("page action result json is invalid", ex);
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream()
                .map(String::valueOf)
                .filter(StringUtils::hasText)
                .toList();
    }

    private void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new PlatformEmbedTokenException(name + " is required");
        }
    }

    private String requiredRequestText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private String normalizePageActionStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "SUCCESS";
        return switch (normalized) {
            case "SUCCESS", "NO_DATA", "PRECONDITION_FAILED", "USER_CANCELLED",
                    "FAILED", "ACTION_NOT_FOUND", "FORBIDDEN", "TIMEOUT" -> normalized;
            // `CANCELLED` was the initial public bridge spelling. Persist the
            // canonical v2 status so new consumers do not need a legacy branch.
            case "CANCELLED" -> "USER_CANCELLED";
            // The first public Angular Page Bridge template used WARN / ERROR.
            // Persist their normalized v2 meaning so Runtime can distinguish a
            // normal business stop from an unavailable bridge.
            case "WARN" -> "PRECONDITION_FAILED";
            case "ERROR" -> "FAILED";
            default -> throw new IllegalArgumentException("unsupported page action status: " + status);
        };
    }

    private PlatformPageActionEventEntity findPageActionEvent(PlatformEmbedSessionEntity session, String requestId) {
        PlatformPageActionEventEntity event = pageActionEventMapper.selectOne(
                new LambdaQueryWrapper<PlatformPageActionEventEntity>()
                        .eq(PlatformPageActionEventEntity::getSessionId, session.getSessionId())
                        .eq(PlatformPageActionEventEntity::getRequestId, requestId)
                        .last("limit 1"));
        return isOwnedPageAction(session, event) && requestId.equals(event.getRequestId()) ? event : null;
    }

    private boolean isOwnedPageAction(PlatformEmbedSessionEntity session, PlatformPageActionEventEntity event) {
        return session != null && event != null
                && Objects.equals(session.getSessionId(), event.getSessionId())
                && Objects.equals(session.getTenantId(), event.getTenantId())
                && Objects.equals(session.getAppId(), event.getAppId())
                && Objects.equals(session.getAgentId(), event.getAgentId())
                && StringUtils.hasText(event.getSessionId()) && StringUtils.hasText(event.getTenantId())
                && StringUtils.hasText(event.getAppId()) && StringUtils.hasText(event.getAgentId());
    }

    private String normalizeEventStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    private boolean canCompleteWithoutExecution(String status) {
        return "USER_CANCELLED".equals(status) || "ACTION_NOT_FOUND".equals(status);
    }

    private boolean isTerminalPageActionStatus(String status) {
        return switch (status) {
            case "SUCCESS", "NO_DATA", "PRECONDITION_FAILED", "USER_CANCELLED",
                    "FAILED", "ACTION_NOT_FOUND", "FORBIDDEN", "TIMEOUT" -> true;
            default -> false;
        };
    }

    private void requireTargetBindingMatchesToken(PageBridgeRebindRequest request,
                                                  PlatformEmbedTokenClaims claims) {
        if (request == null) {
            throw new IllegalArgumentException("page bridge rebind request is required");
        }
        String pageKey = requiredRequestText(request.pageKey(), "pageKey");
        String pageInstanceId = requiredRequestText(request.pageInstanceId(), "pageInstanceId");
        String route = requiredRequestText(request.route(), "route");
        if (!sameText(pageKey, claims.getPageKey())
                || !sameText(pageInstanceId, claims.getPageInstanceId())
                || !sameText(route, claims.getRoute())) {
            throw new PlatformEmbedTokenException("target Page Bridge does not match embed token");
        }
    }

    private boolean sameText(String left, String right) {
        return StringUtils.hasText(left)
                && StringUtils.hasText(right)
                && left.trim().equals(right.trim());
    }

    private boolean routeMatches(String routePattern, String actualRoute) {
        if (!StringUtils.hasText(routePattern) || !StringUtils.hasText(actualRoute)) {
            return false;
        }
        String normalizedPattern = routePattern.trim()
                .replaceAll("([\\\\.\\[\\]{}()+*?^$|])", "\\\\\\\\$1")
                .replaceAll(":([A-Za-z0-9_-]+)", "[^/]+");
        return actualRoute.trim().matches("^" + normalizedPattern + "(?:/)?$");
    }

    private void putIfText(Map<String, Object> map, String key, String value) {
        if (StringUtils.hasText(value)) {
            map.put(key, value);
        }
    }

    private String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private String firstText(String primary, String secondary, String fallback) {
        if (StringUtils.hasText(primary)) {
            return primary;
        }
        if (StringUtils.hasText(secondary)) {
            return secondary;
        }
        return fallback;
    }

    private ResponseEntity<StreamingResponseBody> streamResponse(StreamingResponseBody stream) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    public record EmbedTokenExchangeRequest(
            String projectCode,
            String agentId,
            String pageKey,
            String pageInstanceId,
            String route,
            String origin,
            BusinessPrincipal principal,
            String businessSessionId,
            Long businessSessionExpiresAt) {
        public EmbedTokenExchangeRequest(String projectCode, String agentId, String pageKey,
                                         String pageInstanceId, String route, String origin,
                                         BusinessPrincipal principal) {
            this(projectCode, agentId, pageKey, pageInstanceId, route, origin, principal, null, null);
        }
    }

    public record BusinessSessionRevokeRequest(String projectCode, String businessSessionId) { }

    public record EmbedTokenExchangeResponse(
            String token,
            long expiresIn,
            Map<String, String> sessionHint,
            boolean businessSessionBound,
            boolean businessSessionExpiryApplied) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BusinessPrincipal(
            String externalUserId,
            String globalUserId,
            @JsonAlias("displayName") String userName,
            List<String> roles,
            Map<String, Object> attributes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedChatMessageRequest(@JsonAlias({"content", "text"}) String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedInteractionSubmitRequest(String action,
                                                Map<String, Object> values,
                                                String idempotencyKey) {
        public EmbedInteractionSubmitRequest(String action, Map<String, Object> values) {
            this(action, values, null);
        }
    }

    public record EmbedChatMessageResponse(
            String sessionId,
            String answer,
            String intentType,
            List<String> toolCalls,
            Map<String, Object> metadata,
            Object uiRequest) {
    }

    public record EmbedChatSessionCreateRequest(
            String pageKey,
            String pageInstanceId,
            String route,
            List<String> bridgeActions,
            String sdkVersion) {
    }

    public record EmbedChatSessionResponse(
            String sessionId,
            String agentId,
            Map<String, Object> principal) {
    }

    public record PageBridgeRebindRequest(
            String navigationRequestId,
            String pageKey,
            String pageInstanceId,
            String route,
            List<String> bridgeActions,
            String sdkVersion) {
    }

    public record PageBridgeRebindResponse(
            String sessionId,
            String pageKey,
            String pageInstanceId,
            String route) {
    }

    public record PageActionDispatchRequest(
            String type,
            String protocolVersion,
            String requestId,
            String actionKey,
            String title,
            String nodeId,
            Map<String, Object> target,
            boolean confirm,
            Map<String, Object> args,
            Map<String, Object> metadata) {
    }

    public record PageActionResultRequest(
            String protocolVersion,
            String type,
            String requestId,
            String actionKey,
            String status,
            Object data,
            String message,
            String error,
            Boolean userConfirmed) {
    }

    public record PageActionClaimRequest(String requestId,
                                         String actionKey) {
    }

    public record PageActionClaimResponse(String requestId,
                                          String actionKey,
                                          String status,
                                          boolean claimed) {
    }

    public record PageActionResultResponse(
            String requestId,
            String actionKey,
            String status,
            String error) {
    }
}
