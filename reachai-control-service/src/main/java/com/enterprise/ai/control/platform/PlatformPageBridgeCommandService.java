package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.ai.control.pageworkbench.application.PageCatalogApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionView;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlatformPageBridgeCommandService {
    private static final String DOCUMENT_NAVIGATION_CAPABILITY = "__reachai.document-navigation.v1";

    public static final String NAVIGATE_ACTION = "__reachai.navigate";
    private static final String PAGE_BRIDGE_BUSINESS_TERMINAL =
            "PAGE_BRIDGE_BUSINESS_TERMINAL";
    private static final String CONFIRMATION_TIMEOUT_BEFORE_EXECUTION =
            "Page action confirmation timed out before execution; no business action was started";
    private static final String TIMEOUT_BEFORE_CLAIM = "Page Bridge command was not claimed before its wait timeout";

    private final PlatformEmbedSessionMapper sessionMapper;
    private final PlatformPageActionEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final PageCatalogApplicationService pageCatalog;
    private PageActionEffectReviewService effectReviews;

    @Autowired(required = false)
    void setEffectReviews(PageActionEffectReviewService effectReviews) { this.effectReviews = effectReviews; }

    /**
     * Resolves the trusted Page Bridge identity for an AI Coding smoke test.
     * Callers may name a session id, but project/agent/page identity is always
     * returned from the active Control-owned Embed session rather than accepted
     * from a request body.
     */
    public PageBridgeContextResolution resolveContext(PageBridgeContextResolutionRequest request) {
        if (request == null || !StringUtils.hasText(request.sessionId())
                || !StringUtils.hasText(request.projectCode())) {
            return PageBridgeContextResolution.unresolved(
                    "PAGE_BRIDGE_CONTEXT_REQUIRED",
                    "embedSessionId and projectCode are required");
        }
        PlatformEmbedSessionEntity session = sessionMapper.selectOne(
                Wrappers.<PlatformEmbedSessionEntity>lambdaQuery()
                        .eq(PlatformEmbedSessionEntity::getSessionId, request.sessionId().trim())
                        .eq(PlatformEmbedSessionEntity::getStatus, "ACTIVE")
                        .last("LIMIT 1"));
        if (session == null) {
            return PageBridgeContextResolution.unresolved(
                    "PAGE_BRIDGE_SESSION_NOT_FOUND",
                    "Active embed session not found; open the target business page and retry");
        }
        if (!request.projectCode().trim().equals(session.getProjectCode())) {
            return PageBridgeContextResolution.unresolved(
                    "PAGE_BRIDGE_SESSION_MISMATCH",
                    "Embed session does not belong to this Workflow project");
        }
        return new PageBridgeContextResolution(
                true,
                "PAGE_BRIDGE_CONTEXT_RESOLVED",
                "Active Embed session resolved",
                session.getSessionId(),
                session.getProjectCode(),
                session.getAgentId(),
                session.getPageKey(),
                session.getPageInstanceId(),
                session.getRoute());
    }

    public PageBridgeExecutionResponse execute(PageBridgeExecutionRequest request) {
        validate(request);
        PlatformEmbedSessionEntity session = sessionMapper.selectOne(
                Wrappers.<PlatformEmbedSessionEntity>lambdaQuery()
                        .eq(PlatformEmbedSessionEntity::getSessionId, request.sessionId())
                        .eq(PlatformEmbedSessionEntity::getStatus, "ACTIVE")
                        .last("LIMIT 1"));
        if (session == null || !request.sessionId().equals(session.getSessionId()))
            return failure("PAGE_BRIDGE_SESSION_NOT_FOUND", "Active embed session not found", List.of());
        if (!"EMBED_SESSION".equals(request.identitySource())
                || !StringUtils.hasText(request.identityTenantId()) || !StringUtils.hasText(request.identityUserId())
                || !Objects.equals(session.getTenantId(), request.identityTenantId())
                || !Objects.equals(session.getExternalUserId(), request.identityUserId())) {
            return failure("PAGE_BRIDGE_IDENTITY_MISMATCH", "Embed session identity mismatch", List.of());
        }
        if (!Objects.equals(session.getProjectCode(), request.projectCode())
                || !Objects.equals(session.getAgentId(), request.agentId())) {
            return failure("PAGE_BRIDGE_SESSION_MISMATCH", "Embed session project or Agent mismatch", List.of());
        }
        if (!StringUtils.hasText(session.getPageKey())) {
            return failure("PAGE_BRIDGE_PAGE_NOT_BOUND",
                    "当前页面尚未绑定页面动作，请打开目标业务页面后重试。", List.of());
        }
        // Resolve the minimum confirmation policy from the Control-owned catalog,
        // never from the GraphSpec or the browser command. A missing or inactive
        // action must fail closed before either navigation or action is queued.
        ActionView catalogAction = pageCatalog.findAction(session.getProjectCode(),
                request.targetPageKey(), request.actionKey()).orElse(null);
        var catalogPage = catalogAction == null ? null : pageCatalog.findPageForAction(catalogAction.id())
                .filter(page -> "ACTIVE".equalsIgnoreCase(page.lifecycleStatus())).orElse(null);
        if (catalogAction == null || !"ACTIVE".equalsIgnoreCase(catalogAction.status()) || catalogPage == null) {
            return failure("PAGE_BRIDGE_ACTION_UNAVAILABLE", "Page or action is unavailable", List.of());
        }
        if (catalogAction.allowedAgentIds() != null && !catalogAction.allowedAgentIds().isEmpty()
                && !catalogAction.allowedAgentIds().contains(session.getAgentId())) {
            return failure("PAGE_BRIDGE_ACTION_FORBIDDEN", "Agent is not allowed to execute this page action", List.of());
        }
        String actionContractHash = PageActionContractFingerprint.of(catalogAction, catalogPage, objectMapper);
        if (!StringUtils.hasText(actionContractHash)) {
            return failure("PAGE_BRIDGE_ACTION_UNAVAILABLE", "Page action declaration is incomplete", List.of());
        }
        boolean hasEffectPin = request.effectApprovalId() != null || request.effectApprovalRevision() != null
                || StringUtils.hasText(request.effectCatalogHash());
        if (hasEffectPin && !effectPinMatches(request, actionContractHash)) {
            return failure("PAGE_BRIDGE_EFFECT_UNVERIFIED", "Approved page effect is unavailable or changed", List.of());
        }
        Boolean riskRequiresConfirmation = PageActionRiskFloor.requiresConfirmation(catalogAction);
        if (riskRequiresConfirmation == null) {
            return failure("PAGE_BRIDGE_RISK_UNVERIFIED", "Page action risk is missing or unknown", List.of());
        }
        boolean confirmRequired = hasEffectPin || request.confirmRequired() || catalogAction.confirmRequired() || riskRequiresConfirmation;
        List<PageBridgePhase> phases = new ArrayList<>();
        String traceId = request.supervisorTraceId();
        // Keep the existing opaque per-execution bridge group, while allowing
        // the Embed SDK to correlate an acknowledged action with its own turn.
        // Other entry types / legacy peers retain the historical parent format.
        String parentRequestId = traceId != null && traceId.matches("[0-9a-f]{32}")
                ? "bridge-t1-" + traceId + "-" + UUID.randomUUID().toString().replace("-", "")
                : "bridge-" + UUID.randomUUID();
        boolean crossRoute = !Objects.equals(normalize(session.getPageKey()), normalize(request.targetPageKey()));
        if (StringUtils.hasText(request.resumeNavigationRequestId())) {
            if (!verifiedDocumentNavigation(session, request, actionContractHash)) {
                return failure("PAGE_BRIDGE_DOCUMENT_NAVIGATION_INVALID",
                        "Document navigation is not ready for this action", phases);
            }
            if (crossRoute) return failure("PAGE_BRIDGE_TARGET_NOT_READY", "Target page is not bound", phases);
        } else if (crossRoute && supportsDocumentNavigation(session)) {
            if (!StringUtils.hasText(request.targetRoute()) || traceId == null || !traceId.matches("[0-9a-f]{32}")
                    || request.navigationInteractionId() == null
                    || !request.navigationInteractionId().matches("wfi_[0-9a-f]{32}")) {
                return failure("PAGE_BRIDGE_DOCUMENT_NAVIGATION_INVALID",
                        "Document navigation requires a target route and trusted trace", phases);
            }
            PlatformPageActionEventEntity navigation = enqueue(session, parentRequestId, "NAVIGATE", NAVIGATE_ACTION,
                    request.targetPageKey(), request.targetRoute(), session.getPageInstanceId(),
                    Map.of("pageKey", request.targetPageKey(), "route", request.targetRoute(),
                            "documentNavigation", true, "resumeActionKey", request.actionKey(),
                            "interactionId", request.navigationInteractionId()), true, actionContractHash,
                    request.effectApprovalId(), request.effectApprovalRevision());
            return new PageBridgeExecutionResponse(false, "PAGE_BRIDGE_DOCUMENT_NAVIGATION_REQUIRED", "WAITING",
                    Map.of("navigationRequestId", navigation.getRequestId(), "pageKey", request.targetPageKey(),
                            "route", request.targetRoute()), List.of(phase("NAVIGATE", navigation)));
        }
        if (crossRoute) {
            long navigationDeadline = System.currentTimeMillis()
                    + normalizedTimeout(confirmRequired
                    ? request.confirmationTimeoutMs() : request.executionTimeoutMs());
            PlatformPageActionEventEntity navigation = enqueue(session, parentRequestId, "NAVIGATE", NAVIGATE_ACTION,
                    request.targetPageKey(), request.targetRoute(), session.getPageInstanceId(),
                    Map.of("pageKey", request.targetPageKey(), "route", nullToEmpty(request.targetRoute()),
                            "resumeActionKey", request.actionKey()),
                    confirmRequired, actionContractHash, request.effectApprovalId(), request.effectApprovalRevision());
            PlatformPageActionEventEntity completedNavigation = await(
                    navigation, navigationDeadline, request.executionTimeoutMs());
            phases.add(phase("NAVIGATE", completedNavigation));
            if (!"SUCCESS".equals(completedNavigation.getStatus())) {
                return failure("PAGE_BRIDGE_NAVIGATION_FAILED", resultMessage(completedNavigation), phases);
            }
            Map<String, Object> navigationData = resultData(completedNavigation);
            // The public SDK completes a cross-route navigation by rebinding the
            // session from the target page. Read the persisted session instead
            // of trusting the navigation acknowledgement payload.
            String targetInstance = awaitRegisteredPageInstance(
                    session, navigation, request.targetPageKey(),
                    System.currentTimeMillis() + normalizedTimeout(request.executionTimeoutMs()));
            if (!StringUtils.hasText(targetInstance)) {
                return failure("PAGE_BRIDGE_TARGET_NOT_READY",
                        "Target Page Bridge did not become ready: " + request.targetPageKey(), phases);
            }
            // The target-page rebind already persisted the page identity and
            // action catalog. Keep this in-memory entity aligned for the
            // following PAGE_ACTION enqueue without overwriting its route.
            session.setPageKey(request.targetPageKey());
            session.setPageInstanceId(targetInstance);
            phases.add(new PageBridgePhase("TARGET_READY", null, "SUCCESS", request.targetPageKey(),
                    request.targetRoute(), targetInstance, null, navigationData));
        }

        // Navigation can take long enough for an administrator to change the
        // catalog. Recheck it before dispatching the actual business action.
        ActionView currentAction = pageCatalog.findAction(session.getProjectCode(),
                request.targetPageKey(), request.actionKey()).orElse(null);
        var currentPage = currentAction == null ? null : pageCatalog.findPageForAction(currentAction.id())
                .filter(page -> "ACTIVE".equalsIgnoreCase(page.lifecycleStatus())).orElse(null);
        if (currentAction == null || !"ACTIVE".equalsIgnoreCase(currentAction.status()) || currentPage == null) {
            return failure("PAGE_BRIDGE_ACTION_UNAVAILABLE", "Page or action is unavailable", phases);
        }
        if (currentAction.allowedAgentIds() != null && !currentAction.allowedAgentIds().isEmpty()
                && !currentAction.allowedAgentIds().contains(session.getAgentId())) {
            return failure("PAGE_BRIDGE_ACTION_FORBIDDEN", "Agent is not allowed to execute this page action", phases);
        }
        if (!Objects.equals(actionContractHash, PageActionContractFingerprint.of(currentAction, currentPage, objectMapper))) {
            return failure("PAGE_BRIDGE_ACTION_CHANGED", "Page action declaration changed; retry the request", phases);
        }
        Boolean currentRiskConfirmation = PageActionRiskFloor.requiresConfirmation(currentAction);
        if (currentRiskConfirmation == null) {
            return failure("PAGE_BRIDGE_RISK_UNVERIFIED", "Page action risk is missing or unknown", phases);
        }
        confirmRequired = confirmRequired || currentAction.confirmRequired() || currentRiskConfirmation;

        PlatformPageActionEventEntity action = enqueue(session, parentRequestId, "PAGE_ACTION", request.actionKey(),
                request.targetPageKey(), request.targetRoute(), session.getPageInstanceId(),
                request.args() == null ? Map.of() : request.args(), confirmRequired, actionContractHash,
                request.effectApprovalId(), request.effectApprovalRevision());
        long requestedActionTimeout = confirmRequired
                ? normalizedTimeout(request.confirmationTimeoutMs())
                : normalizedTimeout(request.executionTimeoutMs());
        PlatformPageActionEventEntity completedAction = await(
                action,
                System.currentTimeMillis() + requestedActionTimeout,
                request.executionTimeoutMs());
        phases.add(phase("PAGE_ACTION", completedAction));
        if (!"SUCCESS".equals(completedAction.getStatus())) {
            if (isBusinessTerminalActionStatus(completedAction.getStatus())) {
                return new PageBridgeExecutionResponse(
                        true,
                        PAGE_BRIDGE_BUSINESS_TERMINAL,
                        completedAction.getStatus(),
                        resultData(completedAction),
                        List.copyOf(phases));
            }
            if (("TIMEOUT".equals(completedAction.getStatus()) && !timedOutBeforeClaim(completedAction))
                    || "FAILED".equals(completedAction.getStatus())) {
                return failure("PAGE_BRIDGE_RESULT_UNCONFIRMED",
                        "页面操作结果未确认，请核对业务记录后再决定是否重试。", phases);
            }
            return failure("PAGE_BRIDGE_ACTION_FAILED", resultMessage(completedAction), phases);
        }
        return new PageBridgeExecutionResponse(true, "PAGE_BRIDGE_COMPLETED", "SUCCESS",
                resultData(completedAction), List.copyOf(phases));
    }

    private boolean supportsDocumentNavigation(PlatformEmbedSessionEntity session) {
        try {
            List<String> actions = objectMapper.readValue(session.getBridgeActionsJson(), new TypeReference<>() {});
            return actions != null && actions.contains(DOCUMENT_NAVIGATION_CAPABILITY);
        } catch (Exception invalid) {
            return false;
        }
    }

    private boolean verifiedDocumentNavigation(PlatformEmbedSessionEntity session, PageBridgeExecutionRequest request,
                                               String actionContractHash) {
        PlatformPageActionEventEntity event = eventMapper.selectOne(
                Wrappers.<PlatformPageActionEventEntity>lambdaQuery()
                        .eq(PlatformPageActionEventEntity::getSessionId, session.getSessionId())
                        .eq(PlatformPageActionEventEntity::getRequestId, request.resumeNavigationRequestId())
                        .last("LIMIT 1"));
        if (event == null || !"NAVIGATE".equals(event.getCommandType())
                || !Objects.equals(event.getSessionId(), session.getSessionId())
                || !Objects.equals(event.getRequestId(), request.resumeNavigationRequestId())
                || !NAVIGATE_ACTION.equals(event.getActionKey()) || !"SUCCESS".equals(event.getStatus())
                || !Objects.equals(event.getTenantId(), session.getTenantId())
                || !Objects.equals(event.getAppId(), session.getAppId())
                || !Objects.equals(event.getAgentId(), session.getAgentId())
                || !StringUtils.hasText(event.getActionContractHash())
                || !Objects.equals(event.getActionContractHash(), actionContractHash)
                || !Objects.equals(event.getEffectApprovalId(), request.effectApprovalId())
                || !Objects.equals(event.getEffectApprovalRevision(), request.effectApprovalRevision())
                || !Objects.equals(event.getTargetPageKey(), request.targetPageKey())
                || event.getRequestedAt() == null || event.getRequestedAt().isAfter(LocalDateTime.now())
                || event.getRequestedAt().plusSeconds(120).isBefore(LocalDateTime.now())) {
            return false;
        }
        try {
            Map<String, Object> args = objectMapper.readValue(event.getArgsJson(), new TypeReference<>() {});
            Map<String, Object> result = resultData(event);
            return Boolean.TRUE.equals(args.get("documentNavigation"))
                    && Objects.equals(args.get("resumeActionKey"), request.actionKey())
                    && Objects.equals(args.get("interactionId"), request.navigationInteractionId())
                    && Boolean.TRUE.equals(result.get("ready"))
                    && Objects.equals(result.get("pageInstanceId"), session.getPageInstanceId())
                    && Objects.equals(result.get("pageKey"), session.getPageKey())
                    && Objects.equals(result.get("route"), session.getRoute());
        } catch (Exception invalid) {
            return false;
        }
    }

    private boolean effectPinMatches(PageBridgeExecutionRequest request, String actionHash) {
        if (effectReviews == null || request.effectApprovalId() == null || request.effectApprovalRevision() == null
                || !StringUtils.hasText(request.effectCatalogHash())
                || !Objects.equals(request.effectCatalogHash(), actionHash)) return false;
        var approval = effectReviews.resolveApproved(request.projectCode(), request.targetPageKey(), request.actionKey()).orElse(null);
        return approval != null && approval.active() && "PAGE_LOCAL_DRAFT".equals(approval.effect())
                && Objects.equals(approval.approvalId(), request.effectApprovalId())
                && approval.revision() == request.effectApprovalRevision()
                && Objects.equals(approval.catalogHash(), request.effectCatalogHash());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected PlatformPageActionEventEntity enqueue(PlatformEmbedSessionEntity session,
                                                     String parentRequestId,
                                                     String commandType,
                                                     String actionKey,
                                                     String targetPageKey,
                                                     String targetRoute,
                                                     String targetPageInstanceId,
                                                     Map<String, Object> args,
                                                     boolean confirmRequired,
                                                     String actionContractHash,
                                                     Long effectApprovalId,
                                                     Long effectApprovalRevision) {
        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setRequestId(commandType.toLowerCase() + "-" + UUID.randomUUID());
        event.setSessionId(session.getSessionId());
        event.setTenantId(session.getTenantId());
        event.setAppId(session.getAppId());
        event.setAgentId(session.getAgentId());
        event.setCommandType(commandType);
        event.setParentRequestId(parentRequestId);
        event.setActionKey(actionKey);
        event.setActionContractHash(actionContractHash);
        event.setEffectApprovalId(effectApprovalId);
        event.setEffectApprovalRevision(effectApprovalRevision);
        event.setTitle("NAVIGATE".equals(commandType) ? "Navigate to " + targetPageKey : actionKey);
        event.setArgsJson(json(args));
        event.setTargetPageInstanceId(targetPageInstanceId);
        event.setTargetPageKey(targetPageKey);
        event.setTargetRoute(targetRoute);
        event.setConfirmRequired(confirmRequired);
        event.setStatus("REQUESTED");
        event.setRequestedAt(LocalDateTime.now());
        eventMapper.insert(event);
        return event;
    }

    private PlatformPageActionEventEntity await(PlatformPageActionEventEntity event,
                                                long requestedDeadline,
                                                int executionTimeoutMs) {
        Long executionDeadline = null;
        while (true) {
            PlatformPageActionEventEntity current = eventMapper.selectById(event.getId());
            if (current == null) {
                return event;
            }
            String status = current.getStatus();
            long now = System.currentTimeMillis();
            if (!"REQUESTED".equals(status) && !"EXECUTING".equals(status)) {
                return current;
            }
            if ("REQUESTED".equals(status) && now >= requestedDeadline) {
                if (markTimedOut(current, "REQUESTED")) {
                    return eventMapper.selectById(current.getId());
                }
                continue;
            }
            if ("EXECUTING".equals(status)) {
                if (executionDeadline == null) {
                    executionDeadline = now + normalizedTimeout(executionTimeoutMs);
                }
                if (now >= executionDeadline) {
                    if (markTimedOut(current, "EXECUTING")) {
                        return eventMapper.selectById(current.getId());
                    }
                    continue;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        PlatformPageActionEventEntity current = eventMapper.selectById(event.getId());
        if (current == null) {
            return event;
        }
        if ("REQUESTED".equals(current.getStatus()) || "EXECUTING".equals(current.getStatus())) {
            markTimedOut(current, current.getStatus());
            PlatformPageActionEventEntity refreshed = eventMapper.selectById(current.getId());
            return refreshed == null ? current : refreshed;
        }
        return current;
    }

    private boolean markTimedOut(PlatformPageActionEventEntity event, String expectedStatus) {
        return eventMapper.update(
                null,
                new UpdateWrapper<PlatformPageActionEventEntity>()
                        .eq("id", event.getId())
                        .eq("status", expectedStatus)
                        .set("status", "TIMEOUT")
                        .set("error_message", timeoutMessage(event, expectedStatus))
                        .set("completed_at", LocalDateTime.now())) == 1;
    }

    private String timeoutMessage(PlatformPageActionEventEntity event, String expectedStatus) {
        if ("REQUESTED".equals(expectedStatus) && Boolean.TRUE.equals(event.getConfirmRequired())) {
            return CONFIRMATION_TIMEOUT_BEFORE_EXECUTION;
        }
        if ("REQUESTED".equals(expectedStatus)) {
            return TIMEOUT_BEFORE_CLAIM;
        }
        return "Page Bridge action execution timed out after it was claimed";
    }

    private boolean timedOutBeforeClaim(PlatformPageActionEventEntity event) {
        if (StringUtils.hasText(event.getResultJson())) return false;
        String message = event.getErrorMessage();
        return CONFIRMATION_TIMEOUT_BEFORE_EXECUTION.equals(message) || TIMEOUT_BEFORE_CLAIM.equals(message);
    }

    private long normalizedTimeout(int timeoutMs) {
        return Math.max(1_000L, timeoutMs);
    }

    String awaitRegisteredPageInstance(PlatformEmbedSessionEntity originalSession,
                                      PlatformPageActionEventEntity navigation,
                                      String pageKey,
                                      long deadline) {
        while (System.currentTimeMillis() < deadline) {
            PlatformPageActionEventEntity currentNavigation = eventMapper.selectById(navigation.getId());
            Map<String, Object> data = currentNavigation == null ? Map.of() : resultData(currentNavigation);
            String boundInstance = text(data.get("pageInstanceId"));
            if (currentNavigation == null || !"SUCCESS".equals(currentNavigation.getStatus())
                    || !Boolean.TRUE.equals(data.get("ready")) || !StringUtils.hasText(boundInstance)) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }
            PlatformEmbedSessionEntity current = sessionMapper.selectOne(
                    Wrappers.<PlatformEmbedSessionEntity>lambdaQuery()
                            .eq(PlatformEmbedSessionEntity::getSessionId, originalSession.getSessionId())
                            .eq(PlatformEmbedSessionEntity::getPageKey, pageKey)
                            .eq(PlatformEmbedSessionEntity::getStatus, "ACTIVE")
                            .last("LIMIT 1"));
            if (current != null && Objects.equals(current.getSessionId(), originalSession.getSessionId())
                    && Objects.equals(current.getProjectCode(), originalSession.getProjectCode())
                    && Objects.equals(current.getTenantId(), originalSession.getTenantId())
                    && Objects.equals(current.getAppId(), originalSession.getAppId())
                    && Objects.equals(current.getAgentId(), originalSession.getAgentId())
                    && Objects.equals(current.getExternalUserId(), originalSession.getExternalUserId())
                    && Objects.equals(current.getPageKey(), pageKey)
                    && Objects.equals(current.getPageInstanceId(), boundInstance)) {
                return current.getPageInstanceId();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private PageBridgePhase phase(String phase, PlatformPageActionEventEntity event) {
        return new PageBridgePhase(phase, event.getRequestId(), event.getStatus(), event.getTargetPageKey(),
                event.getTargetRoute(), event.getTargetPageInstanceId(), event.getErrorMessage(), resultData(event));
    }

    private PageBridgeExecutionResponse failure(String code, String message, List<PageBridgePhase> phases) {
        return new PageBridgeExecutionResponse(false, code, message, null, List.copyOf(phases));
    }

    private String resultMessage(PlatformPageActionEventEntity event) {
        return firstText(
                event.getErrorMessage(),
                text(resultData(event).get("error")),
                text(resultData(event).get("message")),
                event.getStatus());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultData(PlatformPageActionEventEntity event) {
        if (!StringUtils.hasText(event.getResultJson())) return Map.of();
        try {
            Map<String, Object> root = objectMapper.readValue(event.getResultJson(), new TypeReference<>() {});
            Object data = root.get("data");
            if (!(data instanceof Map<?, ?> map)) {
                return root;
            }
            Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) map);
            String message = text(root.get("message"));
            if (StringUtils.hasText(message) && !result.containsKey("message")) {
                result.put("message", message);
            }
            if (Boolean.TRUE.equals(root.get("userConfirmed"))) {
                result.put("userConfirmed", true);
            }
            return result;
        } catch (Exception ex) {
            return Map.of("raw", event.getResultJson());
        }
    }

    private boolean isBusinessTerminalActionStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return false;
        }
        return switch (status.trim().toUpperCase()) {
            case "NO_DATA", "PRECONDITION_FAILED", "USER_CANCELLED", "CANCELLED" -> true;
            default -> false;
        };
    }

    private void validate(PageBridgeExecutionRequest request) {
        if (request == null || !StringUtils.hasText(request.sessionId())
                || !StringUtils.hasText(request.projectCode()) || !StringUtils.hasText(request.agentId())
                || !StringUtils.hasText(request.targetPageKey()) || !StringUtils.hasText(request.actionKey())) {
            throw new IllegalArgumentException("sessionId, projectCode, agentId, targetPageKey and actionKey are required");
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { return "{}"; }
    }

    private String text(Object value) { return value == null ? null : String.valueOf(value); }
    private String normalize(String value) { return value == null ? null : value.trim(); }
    private String nullToEmpty(String value) { return value == null ? "" : value; }
    private String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    public record PageBridgeExecutionRequest(String sessionId,
                                             String projectCode,
                                             String agentId,
                                             String currentPageKey,
                                             String targetPageKey,
                                             String targetRoute,
                                             String actionKey,
                                              Map<String, Object> args,
                                              boolean confirmRequired,
                                              int confirmationTimeoutMs,
                                              int executionTimeoutMs,
                                              String identitySource,
                                              String identityTenantId,
                                              String identityUserId,
                                               String supervisorTraceId,
                                               String resumeNavigationRequestId,
                                               String navigationInteractionId,
                                               Long effectApprovalId,
                                               Long effectApprovalRevision,
                                               String effectCatalogHash) {
        public PageBridgeExecutionRequest(String sessionId, String projectCode, String agentId,
                                          String currentPageKey, String targetPageKey, String targetRoute,
                                          String actionKey, Map<String, Object> args, boolean confirmRequired,
                                          int confirmationTimeoutMs, int executionTimeoutMs, String identitySource,
                                          String identityTenantId, String identityUserId, String supervisorTraceId,
                                          String resumeNavigationRequestId, String navigationInteractionId) {
            this(sessionId, projectCode, agentId, currentPageKey, targetPageKey, targetRoute, actionKey, args,
                    confirmRequired, confirmationTimeoutMs, executionTimeoutMs, identitySource, identityTenantId,
                    identityUserId, supervisorTraceId, resumeNavigationRequestId, navigationInteractionId, null, null, null);
        }
        public PageBridgeExecutionRequest(String sessionId, String projectCode, String agentId,
                                          String currentPageKey, String targetPageKey, String targetRoute,
                                          String actionKey, Map<String, Object> args, boolean confirmRequired,
                                          int confirmationTimeoutMs, int executionTimeoutMs, String identitySource,
                                          String identityTenantId, String identityUserId, String supervisorTraceId,
                                          String resumeNavigationRequestId) {
            this(sessionId, projectCode, agentId, currentPageKey, targetPageKey, targetRoute, actionKey,
                    args, confirmRequired, confirmationTimeoutMs, executionTimeoutMs,
                    identitySource, identityTenantId, identityUserId, supervisorTraceId, resumeNavigationRequestId, null);
        }
        public PageBridgeExecutionRequest(String sessionId, String projectCode, String agentId,
                                          String currentPageKey, String targetPageKey, String targetRoute,
                                          String actionKey, Map<String, Object> args, boolean confirmRequired,
                                          int confirmationTimeoutMs, int executionTimeoutMs, String identitySource,
                                          String identityTenantId, String identityUserId, String supervisorTraceId) {
            this(sessionId, projectCode, agentId, currentPageKey, targetPageKey, targetRoute, actionKey,
                    args, confirmRequired, confirmationTimeoutMs, executionTimeoutMs,
                    identitySource, identityTenantId, identityUserId, supervisorTraceId, null, null);
        }
        public PageBridgeExecutionRequest(String sessionId, String projectCode, String agentId,
                                          String currentPageKey, String targetPageKey, String targetRoute,
                                          String actionKey, Map<String, Object> args, boolean confirmRequired,
                                          int confirmationTimeoutMs, int executionTimeoutMs, String identitySource,
                                          String identityTenantId, String identityUserId) {
            this(sessionId, projectCode, agentId, currentPageKey, targetPageKey, targetRoute, actionKey,
                    args, confirmRequired, confirmationTimeoutMs, executionTimeoutMs,
                    identitySource, identityTenantId, identityUserId, null, null, null);
        }
    }

    public record PageBridgeContextResolutionRequest(String sessionId, String projectCode) {
    }

    public record PageBridgeContextResolution(boolean resolved,
                                              String code,
                                              String message,
                                              String sessionId,
                                              String projectCode,
                                              String agentId,
                                              String currentPageKey,
                                              String pageInstanceId,
                                              String route) {
        static PageBridgeContextResolution unresolved(String code, String message) {
            return new PageBridgeContextResolution(false, code, message,
                    null, null, null, null, null, null);
        }
    }

    public record PageBridgeExecutionResponse(boolean success,
                                              String code,
                                              String status,
                                              Object data,
                                              List<PageBridgePhase> phases) {
    }

    public record PageBridgePhase(String phase,
                                  String requestId,
                                  String status,
                                  String pageKey,
                                  String route,
                                  String pageInstanceId,
                                  String error,
                                  Object data) {
    }
}
