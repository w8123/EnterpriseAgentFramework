package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort;
import com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalService;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowInputProtectionService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

/** Supervisor describes the proposed action; execution owns the durable approval lifecycle. */
@Service
public class SupervisorApprovalInteractionService {
    public static final String INTERACTION_PREFIX = RuntimeSupervisorApprovalPort.INTERACTION_PREFIX;
    public static final String SOURCE_TYPE = RuntimeSupervisorApprovalService.SOURCE_TYPE;
    public static final String INTERACTION_TYPE = RuntimeSupervisorApprovalService.INTERACTION_TYPE;
    public static final String WAITING_USER = RuntimeSupervisorApprovalService.WAITING_USER;
    private final RuntimeSupervisorApprovalService approvals;
    private final ObjectMapper objectMapper;
    private final RuntimeWorkflowInputProtectionService workflowInputProtection;

    public SupervisorApprovalInteractionService(RuntimeSupervisorApprovalService approvals, ObjectMapper objectMapper) {
        this(approvals, objectMapper, null);
    }

    @Autowired
    public SupervisorApprovalInteractionService(RuntimeSupervisorApprovalService approvals, ObjectMapper objectMapper,
                                                RuntimeWorkflowInputProtectionService workflowInputProtection) {
        this.approvals = approvals;
        this.objectMapper = objectMapper;
        this.workflowInputProtection = workflowInputProtection;
    }

    public ApprovalRequest create(SupervisorExecutionTraceService.TraceHandle trace,
                                  RuntimeAgentView agent,
                                  RuntimeAgentConfigSnapshot config,
                                  RuntimeAgentWorkflowToolSnapshot tool,
                                  Map<String, Object> input,
                                  Map<String, Object> args,
                                  String reason,
                                  WorkflowExecutionIdentity trustedIdentity) {
        Map<String, Object> visible = workflowInputProtection == null ? args
                : workflowInputProtection.protect(tool.getWorkflowId(), tool.getWorkflowVersionId(), args);
        return createWithPresentation(trace, agent, config, "WORKFLOW",
                tool.getToolName(), tool.getPermissionKey(), tool.getRiskLevel(),
                input, args, visible, reason, trustedIdentity);
    }

    public ApprovalRequest create(SupervisorExecutionTraceService.TraceHandle trace,
                                  RuntimeAgentView agent,
                                  RuntimeAgentConfigSnapshot config,
                                  String toolKind,
                                  String toolName,
                                  String permissionKey,
                                  String riskLevel,
                                  Map<String, Object> input,
                                  Map<String, Object> args,
                                  String reason,
                                  WorkflowExecutionIdentity trustedIdentity) {
        return createWithPresentation(trace, agent, config, toolKind, toolName, permissionKey, riskLevel,
                input, args, args, reason, trustedIdentity);
    }

    private ApprovalRequest createWithPresentation(SupervisorExecutionTraceService.TraceHandle trace,
                                  RuntimeAgentView agent, RuntimeAgentConfigSnapshot config, String toolKind,
                                  String toolName, String permissionKey, String riskLevel,
                                  Map<String, Object> input, Map<String, Object> args, Map<String, Object> visibleArgs,
                                  String reason, WorkflowExecutionIdentity trustedIdentity) {
        LocalDateTime now = LocalDateTime.now();
        String interactionId = INTERACTION_PREFIX + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("schema", "reachai.supervisor-policy-approval.v1");
        checkpoint.put("phase", "CONFIRM");
        checkpoint.put("projectId", agent.projectId());
        checkpoint.put("projectCode", agent.projectCode());
        checkpoint.put("agentId", agent.id());
        checkpoint.put("agentConfigVersionId", config.getId());
        checkpoint.put("toolKind", firstText(toolKind, "TOOL"));
        checkpoint.put("toolName", toolName);
        checkpoint.put("permissionKey", permissionKey);
        checkpoint.put("riskLevel", riskLevel);
        checkpoint.put("args", args == null ? Map.of() : args);
        checkpoint.put("input", input == null ? Map.of() : input);

        Map<String, Object> displayArgs = displayArgs(visibleArgs);

        Map<String, Object> uiRequest = new LinkedHashMap<>();
        uiRequest.put("type", INTERACTION_TYPE);
        uiRequest.put("component", "confirm");
        uiRequest.put("interactionId", interactionId);
        uiRequest.put("title", "确认执行：" + firstText(toolName, "Tool"));
        uiRequest.put("message", confirmationMessage(reason, displayArgs));
        Map<String, Object> cardData = new LinkedHashMap<>();
        cardData.put("toolName", firstText(toolName, ""));
        cardData.put("toolKind", firstText(toolKind, "TOOL"));
        cardData.put("permissionKey", firstText(permissionKey, ""));
        cardData.put("riskLevel", firstText(riskLevel, "WRITE"));
        cardData.put("arguments", displayArgs);
        uiRequest.put("data", cardData);
        uiRequest.put("actions", List.of(
                Map.of("key", "reject", "label", "拒绝", "tone", "danger"),
                Map.of("key", "confirm", "label", "确认执行", "tone", "primary")));
        uiRequest.put("extension", Map.of(
                "kind", "SUPERVISOR_POLICY_APPROVAL",
                "resumeVia", "AGENT_EXECUTE",
                "permissionKey", firstText(permissionKey, ""),
                "riskLevel", firstText(riskLevel, "WRITE")));

        Map<String, Object> continuation = new LinkedHashMap<>();
        continuation.put("agentId", agent.id());
        continuation.put("agentConfigVersionId", config.getId());
        continuation.put("toolKind", firstText(toolKind, "TOOL"));
        continuation.put("toolName", firstText(toolName, ""));
        continuation.put("permissionKey", firstText(permissionKey, ""));
        continuation.put("riskLevel", firstText(riskLevel, "WRITE"));
        continuation.put("resumeVia", "AGENT_EXECUTE");

        approvals.create(new RuntimeSupervisorApprovalService.Create(
                interactionId, agent.id(), trace.traceId(),
                firstText(toolName, firstText(toolKind, "tool").toLowerCase(Locale.ROOT)),
                text(input, "sessionId"), trustedUserId(trustedIdentity),
                trustedIdentity != null && trustedIdentity.projectTrusted() ? firstText(trustedIdentity.getTenantId()) : null,
                writeJson(checkpoint), writeJson(uiRequest), writeJson(continuation), now, now.plusMinutes(15)));
        return new ApprovalRequest(interactionId, uiRequest);
    }


    private String trustedUserId(WorkflowExecutionIdentity identity) {
        return identity != null && identity.userTrusted() ? firstText(identity.userId()) : null;
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

}
