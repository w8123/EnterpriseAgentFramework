package com.enterprise.ai.runtime.interaction;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionMapper;
import com.enterprise.ai.runtime.supervisor.SupervisorApprovalInteractionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeHumanApprovalService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeInteractionSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    public List<PendingHumanApprovalView> listPendingHumanApprovals(String agentId, String userId, int limit) {
        QueryWrapper<RuntimeInteractionSessionEntity> query = new QueryWrapper<RuntimeInteractionSessionEntity>()
                .eq("source_type", SupervisorApprovalInteractionService.SOURCE_TYPE)
                .eq("interaction_type", SupervisorApprovalInteractionService.INTERACTION_TYPE)
                .eq("status", SupervisorApprovalInteractionService.WAITING_USER);
        if (StringUtils.hasText(agentId)) {
            query.eq("agent_id", agentId.trim());
        }
        if (StringUtils.hasText(userId)) {
            query.eq("user_id", userId.trim());
        }
        query.orderByDesc("create_time").last("LIMIT " + effectiveLimit(limit));
        return sessionMapper.selectList(query).stream()
                .map(this::toPendingApproval)
                .toList();
    }

    private PendingHumanApprovalView toPendingApproval(RuntimeInteractionSessionEntity row) {
        Map<String, Object> uiRequest = readMap(row.getUiRequestJson());
        Map<String, Object> checkpoint = readMap(row.getResumeCheckpointJson());
        return new PendingHumanApprovalView(
                row.getId(),
                row.getTraceId(),
                row.getSessionId(),
                row.getUserId(),
                row.getAgentId(),
                firstText(row.getNodeId(), ""),
                row.getStatus(),
                row.getCreateTime(),
                row.getUpdateTime(),
                row.getExpiresAt(),
                text(uiRequest.get("title")),
                text(uiRequest.get("message")),
                uiRequest,
                checkpoint);
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static int effectiveLimit(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    public record PendingHumanApprovalView(String interactionId,
                                           String traceId,
                                           String sessionId,
                                           String userId,
                                           String agentId,
                                           String nodeId,
                                           String status,
                                           LocalDateTime createdAt,
                                           LocalDateTime updatedAt,
                                           LocalDateTime expiresAt,
                                           String title,
                                           String message,
                                           Object uiRequest,
                                           Map<String, Object> state) {
    }
}
