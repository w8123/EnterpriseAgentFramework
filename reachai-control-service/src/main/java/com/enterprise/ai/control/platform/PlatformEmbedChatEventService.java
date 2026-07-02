package com.enterprise.ai.control.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PlatformEmbedChatEventService {

    private final PlatformEmbedChatEventMapper mapper;
    private final ObjectMapper objectMapper;

    public void recordUserMessage(PlatformEmbedSessionEntity session, String message) {
        insert(session, "MESSAGE", "user", message, Map.of("message", message), null);
    }

    public void recordAssistantMessage(PlatformEmbedSessionEntity session,
                                       String answer,
                                       Map<String, Object> payload,
                                       String traceId) {
        insert(session, "MESSAGE", "assistant", answer, payload == null ? Map.of() : payload, traceId);
    }

    private void insert(PlatformEmbedSessionEntity session,
                        String eventType,
                        String role,
                        String content,
                        Map<String, Object> payload,
                        String traceId) {
        PlatformEmbedChatEventEntity entity = new PlatformEmbedChatEventEntity();
        entity.setSessionId(session.getSessionId());
        entity.setEventType(eventType);
        entity.setRole(role);
        entity.setContent(content);
        entity.setPayloadJson(writeJson(payload));
        entity.setTraceId(StringUtils.hasText(traceId) ? traceId : null);
        entity.setCreatedAt(LocalDateTime.now());
        mapper.insert(entity);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
