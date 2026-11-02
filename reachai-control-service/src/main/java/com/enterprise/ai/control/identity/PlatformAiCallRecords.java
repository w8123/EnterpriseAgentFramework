package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 「任务记录」 of a platform account: what its own AI connections did, read back from the
 * PLATFORM_AI_TOOL_CALLED audit events. Arguments and results are never stored there.
 */
@Service
@RequiredArgsConstructor
public class PlatformAiCallRecords {
    static final String CALLED = "PLATFORM_AI_TOOL_CALLED";
    static final String TARGET = "PLATFORM_AI_CONNECTION";
    private static final TypeReference<Map<String, Object>> DETAILS = new TypeReference<>() { };

    private final PlatformAiConnectionMapper connectionMapper;
    private final PlatformAuthAuditEventMapper auditMapper;
    private final ObjectMapper objectMapper;

    public List<CallView> recent(PlatformAuthenticatedSession actor, int limit) {
        if (actor == null || actor.user() == null || actor.user().getId() == null) return List.of();
        Map<String, PlatformAiConnectionEntity> connections = connectionMapper.selectList(
                        new LambdaQueryWrapper<PlatformAiConnectionEntity>()
                                .eq(PlatformAiConnectionEntity::getUserId, actor.user().getId()))
                .stream().collect(Collectors.toMap(entity -> String.valueOf(entity.getId()), Function.identity()));
        if (connections.isEmpty()) return List.of();
        return auditMapper.selectList(new LambdaQueryWrapper<PlatformAuthAuditEventEntity>()
                        .eq(PlatformAuthAuditEventEntity::getTargetType, TARGET)
                        .in(PlatformAuthAuditEventEntity::getTargetId, connections.keySet())
                        .eq(PlatformAuthAuditEventEntity::getEventType, CALLED)
                        .orderByDesc(PlatformAuthAuditEventEntity::getId)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 500))))
                .stream().map(event -> view(event, connections.get(event.getTargetId()))).toList();
    }

    private CallView view(PlatformAuthAuditEventEntity event, PlatformAiConnectionEntity connection) {
        Map<String, Object> details = details(event.getDetailsJson());
        Object elapsed = details.get("elapsedMs");
        return new CallView(event.getId(), connection == null ? null : connection.getId(),
                connection == null ? null : connection.getLabel(),
                text(details.get("toolKind")), text(details.get("toolName")),
                Boolean.TRUE.equals(details.get("success")),
                elapsed instanceof Number number ? number.longValue() : null,
                text(details.getOrDefault("errorCode", details.get("errorCategory"))), event.getCreatedAt());
    }

    private Map<String, Object> details(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, DETAILS);
        } catch (Exception malformed) {
            return Map.of();
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record CallView(Long id, Long connectionId, String connectionLabel, String toolKind, String toolName,
                           boolean success, Long latencyMs, String errorCode, LocalDateTime createdAt) { }
}
