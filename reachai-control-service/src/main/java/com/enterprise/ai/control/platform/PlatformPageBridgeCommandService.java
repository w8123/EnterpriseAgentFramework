package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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

    public static final String NAVIGATE_ACTION = "__reachai.navigate";

    private final PlatformEmbedSessionMapper sessionMapper;
    private final PlatformEmbedSessionService sessionService;
    private final PlatformPageActionEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public PageBridgeExecutionResponse execute(PageBridgeExecutionRequest request) {
        validate(request);
        PlatformEmbedSessionEntity session = sessionMapper.selectOne(
                Wrappers.<PlatformEmbedSessionEntity>lambdaQuery()
                        .eq(PlatformEmbedSessionEntity::getSessionId, request.sessionId())
                        .eq(PlatformEmbedSessionEntity::getStatus, "ACTIVE")
                        .last("LIMIT 1"));
        if (session == null) return failure("PAGE_BRIDGE_SESSION_NOT_FOUND", "Active embed session not found", List.of());
        if (!Objects.equals(session.getProjectCode(), request.projectCode())
                || !Objects.equals(session.getAgentId(), request.agentId())) {
            return failure("PAGE_BRIDGE_SESSION_MISMATCH", "Embed session project or Agent mismatch", List.of());
        }
        long deadline = System.currentTimeMillis() + Math.max(1000, request.timeoutMs());
        List<PageBridgePhase> phases = new ArrayList<>();
        String parentRequestId = "bridge-" + UUID.randomUUID();
        boolean crossRoute = !Objects.equals(normalize(session.getPageKey()), normalize(request.targetPageKey()));
        if (crossRoute) {
            LocalDateTime navigationStarted = LocalDateTime.now();
            PlatformPageActionEventEntity navigation = enqueue(session, parentRequestId, "NAVIGATE", NAVIGATE_ACTION,
                    request.targetPageKey(), request.targetRoute(), session.getPageInstanceId(),
                    Map.of("pageKey", request.targetPageKey(), "route", nullToEmpty(request.targetRoute())), false);
            PlatformPageActionEventEntity completedNavigation = await(navigation, deadline);
            phases.add(phase("NAVIGATE", completedNavigation));
            if (!"SUCCESS".equals(completedNavigation.getStatus())) {
                return failure("PAGE_BRIDGE_NAVIGATION_FAILED", resultMessage(completedNavigation), phases);
            }
            Map<String, Object> navigationData = resultData(completedNavigation);
            String targetInstance = text(navigationData.get("pageInstanceId"));
            if (!StringUtils.hasText(targetInstance)) {
                targetInstance = awaitRegisteredPageInstance(
                        session.getSessionId(), request.targetPageKey(), navigationStarted, deadline);
            }
            if (!StringUtils.hasText(targetInstance)) {
                return failure("PAGE_BRIDGE_TARGET_NOT_READY",
                        "Target Page Bridge did not become ready: " + request.targetPageKey(), phases);
            }
            sessionService.updateBridge(session, request.targetPageKey(), targetInstance,
                    firstText(text(navigationData.get("route")), request.targetRoute()));
            phases.add(new PageBridgePhase("TARGET_READY", null, "SUCCESS", request.targetPageKey(),
                    request.targetRoute(), targetInstance, null, navigationData));
        }

        PlatformPageActionEventEntity action = enqueue(session, parentRequestId, "PAGE_ACTION", request.actionKey(),
                request.targetPageKey(), request.targetRoute(), session.getPageInstanceId(),
                request.args() == null ? Map.of() : request.args(), request.confirmRequired());
        PlatformPageActionEventEntity completedAction = await(action, deadline);
        phases.add(phase("PAGE_ACTION", completedAction));
        if (!"SUCCESS".equals(completedAction.getStatus())) {
            return failure("PAGE_BRIDGE_ACTION_FAILED", resultMessage(completedAction), phases);
        }
        return new PageBridgeExecutionResponse(true, "PAGE_BRIDGE_COMPLETED", "SUCCESS",
                resultData(completedAction), List.copyOf(phases));
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
                                                     boolean confirmRequired) {
        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setRequestId(commandType.toLowerCase() + "-" + UUID.randomUUID());
        event.setSessionId(session.getSessionId());
        event.setTenantId(session.getTenantId());
        event.setAppId(session.getAppId());
        event.setAgentId(session.getAgentId());
        event.setCommandType(commandType);
        event.setParentRequestId(parentRequestId);
        event.setActionKey(actionKey);
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

    private PlatformPageActionEventEntity await(PlatformPageActionEventEntity event, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            PlatformPageActionEventEntity current = eventMapper.selectById(event.getId());
            if (current != null && !"REQUESTED".equals(current.getStatus())) return current;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        event.setStatus("TIMEOUT");
        event.setErrorMessage("Page Bridge command timed out");
        event.setCompletedAt(LocalDateTime.now());
        eventMapper.updateById(event);
        return event;
    }

    private String awaitRegisteredPageInstance(String sessionId,
                                               String pageKey,
                                               LocalDateTime after,
                                               long deadline) {
        while (System.currentTimeMillis() < deadline) {
            PlatformEmbedSessionEntity current = sessionMapper.selectOne(
                    Wrappers.<PlatformEmbedSessionEntity>lambdaQuery()
                            .eq(PlatformEmbedSessionEntity::getSessionId, sessionId)
                            .eq(PlatformEmbedSessionEntity::getPageKey, pageKey)
                            .eq(PlatformEmbedSessionEntity::getStatus, "ACTIVE")
                            .ge(PlatformEmbedSessionEntity::getUpdatedAt, after)
                            .last("LIMIT 1"));
            if (current != null && StringUtils.hasText(current.getPageInstanceId())) {
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
        return firstText(event.getErrorMessage(), text(resultData(event).get("error")), event.getStatus());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultData(PlatformPageActionEventEntity event) {
        if (!StringUtils.hasText(event.getResultJson())) return Map.of();
        try {
            Map<String, Object> root = objectMapper.readValue(event.getResultJson(), new TypeReference<>() {});
            Object data = root.get("data");
            return data instanceof Map<?, ?> map ? (Map<String, Object>) map : root;
        } catch (Exception ex) {
            return Map.of("raw", event.getResultJson());
        }
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
                                             int timeoutMs) {
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
