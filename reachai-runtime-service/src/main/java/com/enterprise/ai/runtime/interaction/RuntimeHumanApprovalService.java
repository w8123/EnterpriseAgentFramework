package com.enterprise.ai.runtime.interaction;

import com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalService;
import com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalService.PendingApproval;
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

    private final RuntimeSupervisorApprovalService approvals;
    private final ObjectMapper objectMapper;

    public List<PendingHumanApprovalView> listPendingHumanApprovals(String agentId, String userId, int limit) {
        return approvals.pending(agentId, userId, limit).stream()
                .map(this::toPendingApproval)
                .toList();
    }

    private PendingHumanApprovalView toPendingApproval(PendingApproval row) {
        Map<String, Object> uiRequest = readMap(row.uiRequestJson());
        Map<String, Object> publicState = readMap(row.publicStateJson());
        return new PendingHumanApprovalView(
                row.id(),
                row.traceId(),
                row.sessionId(),
                row.userId(),
                row.agentId(),
                firstText(row.nodeId(), ""),
                row.status(),
                row.createTime(),
                row.updateTime(),
                row.expiresAt(),
                text(uiRequest.get("title")),
                text(uiRequest.get("message")),
                uiRequest,
                publicState);
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
