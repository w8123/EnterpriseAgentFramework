package com.enterprise.ai.agent.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.model.ChatResponse;
import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmbedAuditEventService {

    private final EmbedChatEventMapper chatEventMapper;
    private final PageActionEventMapper pageActionEventMapper;
    private final ObjectMapper objectMapper;

    public void recordUserMessage(EmbedSessionEntity session, String message) {
        if (session == null || !StringUtils.hasText(message)) {
            return;
        }
        recordChatEvent(session.getSessionId(), "message.user", "user", message, Map.of("message", message), null);
    }

    public void recordAssistantResponse(EmbedSessionEntity session, ChatResponse response) {
        if (session == null || response == null) {
            return;
        }
        if (StringUtils.hasText(response.getAnswer())) {
            recordChatEvent(session.getSessionId(), "message.assistant", "assistant", response.getAnswer(), response, traceId(response));
        }
        UiRequestPayload uiRequest = response.getUiRequest();
        if (uiRequest != null) {
            recordChatEvent(session.getSessionId(), "ui.requested", "assistant", uiRequest.getMessage(), uiRequest, uiRequest.getTraceId());
            recordPageActionRequestIfPresent(session, uiRequest);
        }
    }

    public void recordStreamEvent(EmbedSessionEntity session, EmbedChatStreamEvent event) {
        if (session == null || event == null) {
            return;
        }
        recordChatEvent(session.getSessionId(), event.type(), "assistant", null, event.data(), null);
        if ("page.action.requested".equals(event.type()) && event.data() instanceof Map<?, ?> map) {
            recordPageActionRequest(session, normalizeMap(map));
        }
    }

    public PageActionEventEntity recordPageActionResult(EmbedSessionEntity session,
                                                        String requestId,
                                                        Map<String, Object> result) {
        if (session == null || !StringUtils.hasText(requestId)) {
            throw new EmbedTokenException("page action result requestId is required");
        }
        PageActionEventEntity entity = pageActionEventMapper.selectOne(Wrappers.<PageActionEventEntity>lambdaQuery()
                .eq(PageActionEventEntity::getSessionId, session.getSessionId())
                .eq(PageActionEventEntity::getRequestId, requestId)
                .last("LIMIT 1"));
        if (entity == null) {
            throw new EmbedTokenException("page action request does not belong to current session");
        }
        entity.setActionKey(asString(result.get("actionKey")));
        entity.setStatus(firstNonBlank(asString(result.get("status")), "UNKNOWN"));
        entity.setResultJson(writeJson(result));
        entity.setErrorMessage(asString(result.get("error")));
        entity.setCompletedAt(LocalDateTime.now());
        pageActionEventMapper.updateById(entity);
        recordChatEvent(session.getSessionId(), "page.action.result", "page", null, result, null);
        return entity;
    }

    private void recordPageActionRequestIfPresent(EmbedSessionEntity session, UiRequestPayload uiRequest) {
        Object pageAction = uiRequest.getExtension() == null ? null : uiRequest.getExtension().get("pageActionRequest");
        if (pageAction instanceof Map<?, ?> map) {
            recordPageActionRequest(session, normalizeMap(map));
        }
    }

    private void recordPageActionRequest(EmbedSessionEntity session, Map<String, Object> request) {
        String requestId = asString(request.get("requestId"));
        if (!StringUtils.hasText(requestId)) {
            return;
        }
        PageActionEventEntity existing = pageActionEventMapper.selectOne(Wrappers.<PageActionEventEntity>lambdaQuery()
                .eq(PageActionEventEntity::getSessionId, session.getSessionId())
                .eq(PageActionEventEntity::getRequestId, requestId)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        PageActionEventEntity entity = new PageActionEventEntity();
        entity.setRequestId(requestId);
        entity.setSessionId(session.getSessionId());
        entity.setTenantId(session.getTenantId());
        entity.setAppId(session.getAppId());
        entity.setAgentId(session.getAgentId());
        entity.setNodeId(asString(request.get("nodeId")));
        entity.setActionKey(asString(request.get("actionKey")));
        entity.setTitle(asString(request.get("title")));
        entity.setArgsJson(writeJson(request.get("args")));
        entity.setTargetPageInstanceId(targetPageInstanceId(request, session.getPageInstanceId()));
        entity.setConfirmRequired(Boolean.TRUE.equals(request.get("confirm")));
        entity.setStatus("REQUESTED");
        entity.setRequestedAt(LocalDateTime.now());
        pageActionEventMapper.insert(entity);
    }

    private void recordChatEvent(String sessionId, String eventType, String role, String content, Object payload, String traceId) {
        EmbedChatEventEntity entity = new EmbedChatEventEntity();
        entity.setSessionId(sessionId);
        entity.setEventType(eventType);
        entity.setRole(role);
        entity.setContent(content);
        entity.setPayloadJson(writeJson(payload));
        entity.setTraceId(traceId);
        entity.setCreatedAt(LocalDateTime.now());
        chatEventMapper.insert(entity);
    }

    private String targetPageInstanceId(Map<String, Object> request, String fallback) {
        Object target = request.get("target");
        if (target instanceof Map<?, ?> map) {
            String pageInstanceId = asString(map.get("pageInstanceId"));
            if (StringUtils.hasText(pageInstanceId)) {
                return pageInstanceId;
            }
        }
        return fallback;
    }

    private Map<String, Object> normalizeMap(Map<?, ?> raw) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        return normalized;
    }

    private String traceId(ChatResponse response) {
        Object traceId = response.getMetadata() == null ? null : response.getMetadata().get("traceId");
        return asString(traceId);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }
}
