package com.enterprise.ai.runtime.interaction;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeHumanApprovalService {

    static final String SKILL_PREFIX = "graph-approval:";
    private static final String PENDING = "PENDING";
    private static final String SUBMITTED = "SUBMITTED";
    private static final String CANCELLED = "CANCELLED";
    private static final String EXPIRED = "EXPIRED";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeSkillInteractionMapper mapper;
    private final ObjectMapper objectMapper;

    public List<PendingHumanApprovalView> listPendingHumanApprovals(Long agentId, String userId, int limit) {
        QueryWrapper<RuntimeSkillInteractionEntity> query = new QueryWrapper<RuntimeSkillInteractionEntity>()
                .eq("status", PENDING)
                .likeRight("skill_name", SKILL_PREFIX)
                .orderByDesc("created_at")
                .last("LIMIT " + effectiveLimit(limit));
        if (agentId != null) {
            query.eq("agent_id", agentId);
        }
        if (StringUtils.hasText(userId)) {
            query.eq("user_id", userId.trim());
        }
        return mapper.selectList(query).stream()
                .map(this::toPendingApproval)
                .toList();
    }

    public AgentResultView submitHumanApproval(String interactionId, SubmitRequest request) {
        RuntimeSkillInteractionEntity row = requireApproval(interactionId);
        AgentResultView closed = rejectClosed(row);
        if (closed != null) {
            return closed;
        }
        String route = isRejected(request) ? "rejected" : "approved";
        row.setStatus(SUBMITTED);
        row.setUpdatedAt(LocalDateTime.now());
        row.setSlotState(writeJson(Map.of(
                "phase", "decision",
                "route", route,
                "decision", route,
                "submittedBy", request == null ? "" : firstText(request.userId(), ""),
                "submittedAt", LocalDateTime.now().toString(),
                "values", request == null || request.values() == null ? Map.of() : request.values())));
        mapper.updateById(row);
        return new AgentResultView(
                true,
                "approved".equals(route) ? "审批已通过" : "审批已拒绝",
                List.of(),
                Map.of(),
                baseMeta(row, route, route),
                null);
    }

    public AgentResultView cancelHumanApproval(String interactionId, String userId) {
        RuntimeSkillInteractionEntity row = requireApproval(interactionId);
        AgentResultView closed = rejectClosed(row);
        if (closed != null) {
            return closed;
        }
        row.setStatus(CANCELLED);
        row.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(row);
        return new AgentResultView(true, "已取消审批", List.of(), Map.of(), baseMeta(row, "cancelled", "cancelled"), null);
    }

    private PendingHumanApprovalView toPendingApproval(RuntimeSkillInteractionEntity row) {
        Map<String, Object> uiRequest = readMap(row.getUiPayload());
        return new PendingHumanApprovalView(
                row.getId(),
                row.getTraceId(),
                row.getSessionId(),
                row.getUserId(),
                row.getAgentId(),
                nodeId(row.getSkillName()),
                row.getStatus(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getExpiresAt(),
                text(uiRequest.get("title")),
                text(uiRequest.get("message")),
                uiRequest,
                readMap(row.getSlotState()));
    }

    private RuntimeSkillInteractionEntity requireApproval(String interactionId) {
        if (!StringUtils.hasText(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }
        RuntimeSkillInteractionEntity row = mapper.selectById(interactionId.trim());
        if (row == null || !StringUtils.hasText(row.getSkillName()) || !row.getSkillName().startsWith(SKILL_PREFIX)) {
            throw new IllegalArgumentException("human approval not found: " + interactionId.trim());
        }
        return row;
    }

    private AgentResultView rejectClosed(RuntimeSkillInteractionEntity row) {
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(LocalDateTime.now())) {
            row.setStatus(EXPIRED);
            row.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(row);
            return new AgentResultView(false, "审批已超时，请重新发起流程", List.of(), Map.of(),
                    baseMeta(row, "timeout", "timeout"), null);
        }
        if (!PENDING.equalsIgnoreCase(row.getStatus())) {
            return new AgentResultView(false, "该审批已处理，无法重复提交", List.of(), Map.of(),
                    baseMeta(row, "closed", "closed"), null);
        }
        return null;
    }

    private boolean isRejected(SubmitRequest request) {
        String action = request == null ? null : request.action();
        if ("reject".equalsIgnoreCase(action) || "rejected".equalsIgnoreCase(action)) {
            return true;
        }
        Object confirm = request == null || request.values() == null ? null : request.values().get("confirm");
        if (confirm instanceof Boolean value) {
            return !value;
        }
        return confirm != null && "false".equalsIgnoreCase(String.valueOf(confirm).trim());
    }

    private Map<String, Object> baseMeta(RuntimeSkillInteractionEntity row, String decision, String route) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("interactionId", row.getId());
        metadata.put("traceId", row.getTraceId());
        metadata.put("sessionId", row.getSessionId());
        metadata.put("nodeId", nodeId(row.getSkillName()));
        metadata.put("decision", decision);
        metadata.put("route", route);
        metadata.put("type", "humanApproval");
        return metadata;
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("human approval json serialization failed: " + ex.getMessage(), ex);
        }
    }

    private static int effectiveLimit(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    private static String nodeId(String skillName) {
        return skillName != null && skillName.startsWith(SKILL_PREFIX)
                ? skillName.substring(SKILL_PREFIX.length())
                : "";
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

    public record SubmitRequest(String action,
                                Map<String, Object> values,
                                String userId,
                                String sessionId) {
    }

    public record PendingHumanApprovalView(String interactionId,
                                           String traceId,
                                           String sessionId,
                                           String userId,
                                           Long agentId,
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

    public record AgentResultView(boolean success,
                                  String answer,
                                  List<StepRecord> steps,
                                  Map<String, Object> toolResults,
                                  Map<String, Object> metadata,
                                  Object uiRequest) {
    }

    public record StepRecord(String name, String detail) {
    }
}
