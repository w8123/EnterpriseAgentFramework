package com.enterprise.ai.control.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/** Writes auditable metadata only; callers must never pass credentials or raw provider configuration. */
@Service
@RequiredArgsConstructor
public class PlatformAuthAuditService {

    private final PlatformAuthAuditEventMapper auditEventMapper;
    private final ObjectMapper objectMapper;

    public void record(
            PlatformAuthenticatedSession actor,
            String eventType,
            String targetType,
            String targetId,
            Map<String, ?> details) {
        PlatformAuthAuditEventEntity event = new PlatformAuthAuditEventEntity();
        event.setEventType(eventType);
        event.setActorUserId(actor == null || actor.user() == null ? null : actor.user().getId());
        event.setActorSessionId(actor == null ? null : actor.sessionId());
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setDetailsJson(toJson(details));
        event.setCreatedAt(LocalDateTime.now());
        auditEventMapper.insert(event);
    }

    private String toJson(Map<String, ?> details) {
        try {
            return objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("platform auth audit details must be serializable", exception);
        }
    }
}
