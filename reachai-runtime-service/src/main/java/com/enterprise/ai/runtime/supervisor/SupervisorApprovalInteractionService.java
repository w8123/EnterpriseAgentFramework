package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.interaction.RuntimeSkillInteractionEntity;
import com.enterprise.ai.runtime.interaction.RuntimeSkillInteractionMapper;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter.PolicyApprovalGrant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SupervisorApprovalInteractionService {

    public static final String INTERACTION_PREFIX = "spv_";
    public static final String SKILL_PREFIX = "supervisor-policy:";
    private static final String PENDING = "PENDING";
    private static final String SUBMITTED = "SUBMITTED";
    private static final String EXPIRED = "EXPIRED";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeSkillInteractionMapper mapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public ApprovalRequest create(SupervisorExecutionTraceService.TraceHandle trace,
                                  RuntimeAgentView agent,
                                  RuntimeAgentConfigVersionEntity config,
                                  RuntimeAgentWorkflowToolEntity tool,
                                  Map<String, Object> input,
                                  Map<String, Object> args,
                                  String reason) {
        LocalDateTime now = LocalDateTime.now();
        String interactionId = INTERACTION_PREFIX + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "CONFIRM");
        state.put("agentId", agent.id());
        state.put("agentConfigVersionId", config.getId());
        state.put("toolName", tool.getToolName());
        state.put("permissionKey", tool.getPermissionKey());
        state.put("riskLevel", tool.getRiskLevel());
        state.put("args", args == null ? Map.of() : args);
        state.put("input", input == null ? Map.of() : input);

        Map<String, Object> displayArgs = displayArgs(args);

        Map<String, Object> uiRequest = new LinkedHashMap<>();
        uiRequest.put("type", "CONFIRM_ACTION");
        uiRequest.put("component", "confirm");
        uiRequest.put("interactionId", interactionId);
        uiRequest.put("title", "确认执行：" + firstText(tool.getToolName(), "Workflow Tool"));
        uiRequest.put("message", confirmationMessage(reason, displayArgs));
        Map<String, Object> cardData = new LinkedHashMap<>();
        cardData.put("toolName", firstText(tool.getToolName(), ""));
        cardData.put("permissionKey", firstText(tool.getPermissionKey(), ""));
        cardData.put("riskLevel", firstText(tool.getRiskLevel(), "WRITE"));
        cardData.put("arguments", displayArgs);
        uiRequest.put("data", cardData);
        uiRequest.put("actions", List.of(
                Map.of("key", "reject", "label", "拒绝", "tone", "danger"),
                Map.of("key", "confirm", "label", "确认执行", "tone", "primary")));
        uiRequest.put("extension", Map.of(
                "kind", "SUPERVISOR_POLICY_APPROVAL",
                "resumeVia", "AGENT_EXECUTE",
                "permissionKey", firstText(tool.getPermissionKey(), ""),
                "riskLevel", firstText(tool.getRiskLevel(), "WRITE")));

        RuntimeSkillInteractionEntity row = new RuntimeSkillInteractionEntity();
        row.setId(interactionId);
        row.setTraceId(trace.traceId());
        row.setSessionId(text(input, "sessionId"));
        row.setUserId(firstText(text(input, "userId"), text(input, "externalUserId")));
        row.setAgentId(agent.id());
        row.setSkillName(SKILL_PREFIX + firstText(tool.getToolName(), "workflow"));
        row.setStatus(PENDING);
        row.setSlotState(writeJson(state));
        row.setPendingKeys(writeJson(List.of("confirm")));
        row.setUiPayload(writeJson(uiRequest));
        row.setSpecSnapshot(writeJson(Map.of(
                "agentConfigVersionId", config.getId(),
                "toolName", firstText(tool.getToolName(), ""),
                "permissionKey", firstText(tool.getPermissionKey(), ""),
                "riskLevel", firstText(tool.getRiskLevel(), "WRITE"))));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setExpiresAt(now.plusMinutes(15));
        mapper.insert(row);
        return new ApprovalRequest(interactionId, uiRequest);
    }

    @Transactional
    public ResumeDecision prepareResume(String interactionId, Map<String, Object> submission) {
        RuntimeSkillInteractionEntity row = requirePending(interactionId);
        Map<String, Object> state = readMap(row.getSlotState());
        Map<String, Object> uiSubmit = mapValue(submission == null ? null : submission.get("uiSubmit"));
        Map<String, Object> values = mapValue(uiSubmit.get("values"));
        String action = text(uiSubmit.get("action"));
        boolean rejected = "reject".equalsIgnoreCase(action)
                || "cancel".equalsIgnoreCase(action)
                || Boolean.FALSE.equals(values.get("confirm"));
        boolean confirmed = "confirm".equalsIgnoreCase(action)
                || "approve".equalsIgnoreCase(action)
                || "submit".equalsIgnoreCase(action)
                || Boolean.TRUE.equals(values.get("confirm"));
        if (!confirmed && !rejected) {
            throw new IllegalArgumentException("Supervisor approval requires an explicit confirm or reject action");
        }

        String submittedSessionId = text(submission == null ? null : submission.get("sessionId"));
        if (StringUtils.hasText(row.getSessionId())
                && !row.getSessionId().equals(submittedSessionId)) {
            throw new IllegalArgumentException("Supervisor approval session mismatch");
        }
        String submittedUserId = firstText(
                text(submission == null ? null : submission.get("userId")),
                text(submission == null ? null : submission.get("externalUserId")));
        if (StringUtils.hasText(row.getUserId())
                && !row.getUserId().equals(submittedUserId)) {
            throw new IllegalArgumentException("Supervisor approval user mismatch");
        }

        Map<String, Object> decisionState = new LinkedHashMap<>(state);
        decisionState.put("phase", "DECISION");
        decisionState.put("decision", confirmed ? "approved" : "rejected");
        decisionState.put("submittedAt", LocalDateTime.now().toString());
        decisionState.put("submittedBy", firstText(submittedUserId, row.getUserId(), "anonymous"));
        row.setStatus(SUBMITTED);
        row.setSlotState(writeJson(decisionState));
        row.setPendingKeys(null);
        row.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(row);

        Map<String, Object> originalInput = mapValue(state.get("input"));
        if (StringUtils.hasText(row.getTraceId())) {
            originalInput.put("traceId", row.getTraceId());
        }
        if (!confirmed) {
            return new ResumeDecision(false, true, row.getAgentId(), originalInput, null,
                    "用户已拒绝执行该 Workflow Tool");
        }
        if (StringUtils.hasText(submittedSessionId)) originalInput.put("sessionId", submittedSessionId);
        String permissionKey = text(state.get("permissionKey"));
        PolicyApprovalGrant grant = new PolicyApprovalGrant(
                row.getId(), permissionKey, text(state.get("toolName")), mapValue(state.get("args")),
                firstText(submittedUserId, row.getUserId(), "anonymous"));
        return new ResumeDecision(true, false, row.getAgentId(), originalInput, grant,
                "审批已通过，继续执行原请求");
    }

    private RuntimeSkillInteractionEntity requirePending(String interactionId) {
        if (!StringUtils.hasText(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }
        RuntimeSkillInteractionEntity row = mapper.selectById(interactionId.trim());
        if (row == null || !StringUtils.hasText(row.getSkillName())
                || !row.getSkillName().startsWith(SKILL_PREFIX)) {
            throw new IllegalArgumentException("Supervisor approval not found: " + interactionId.trim());
        }
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(LocalDateTime.now())) {
            row.setStatus(EXPIRED);
            row.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(row);
            throw new IllegalArgumentException("Supervisor approval expired: " + interactionId.trim());
        }
        if (!PENDING.equalsIgnoreCase(row.getStatus())) {
            throw new IllegalArgumentException("Supervisor approval is already closed: " + interactionId.trim());
        }
        return row;
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, MAP_TYPE));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Supervisor approval state", ex);
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (value == null) return new LinkedHashMap<>();
        return new LinkedHashMap<>(objectMapper.convertValue(value, MAP_TYPE));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Supervisor approval JSON serialization failed", ex);
        }
    }

    private Map<String, Object> displayArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> display = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (display.size() >= 6 || !StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().trim();
            String lower = key.toLowerCase(Locale.ROOT);
            Object value = entry.getValue();
            if (lower.contains("secret") || lower.contains("token") || lower.contains("password")
                    || lower.contains("authorization") || lower.contains("credential")
                    || lower.contains("apikey") || lower.contains("api_key") || lower.contains("cookie")) {
                display.put(key, "[已隐藏]");
            } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                String rendered = String.valueOf(value);
                display.put(key, rendered.length() <= 100 ? value : rendered.substring(0, 100) + "…");
            }
        }
        return display;
    }

    private String confirmationMessage(String reason, Map<String, Object> displayArgs) {
        String base = firstText(reason, "该操作需要用户确认");
        if (displayArgs == null || displayArgs.isEmpty()) {
            return base;
        }
        StringJoiner joiner = new StringJoiner("，");
        displayArgs.forEach((key, value) -> joiner.add(key + "=" + value));
        return base + "。操作参数：" + joiner;
    }

    private String text(Map<String, Object> source, String key) {
        return source == null ? null : text(source.get(key));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    public record ApprovalRequest(String interactionId, Map<String, Object> uiRequest) {
    }

    public record ResumeDecision(boolean approved,
                                 boolean rejected,
                                 String agentId,
                                 Map<String, Object> originalInput,
                                 PolicyApprovalGrant grant,
                                 String message) {
    }
}
