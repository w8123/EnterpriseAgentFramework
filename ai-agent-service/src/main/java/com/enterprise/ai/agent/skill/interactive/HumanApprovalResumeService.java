package com.enterprise.ai.agent.skill.interactive;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.ChatRequest;
import com.enterprise.ai.agent.model.interactive.UiSubmitPayload;
import com.enterprise.ai.agent.runtime.AgentRuntimeRequest;
import com.enterprise.ai.agent.runtime.AgentRuntimeResult;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.enterprise.ai.agent.runtime.LangGraph4jRuntimeAdapter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class HumanApprovalResumeService {

    public static final String SKILL_PREFIX = "graph-approval:";

    private final SkillInteractionMapper skillInteractionMapper;
    private final ObjectMapper objectMapper;
    private final LangGraph4jRuntimeAdapter langGraph4jRuntimeAdapter;

    public Optional<AgentResult> resumeIfApproval(ChatRequest request) {
        String interactionId = request == null ? null : request.getInteractionId();
        if (interactionId == null || interactionId.isBlank()) {
            return Optional.empty();
        }
        SkillInteractionEntity row = skillInteractionMapper.selectById(interactionId);
        if (row == null || row.getSkillName() == null || !row.getSkillName().startsWith(SKILL_PREFIX)) {
            return Optional.empty();
        }
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(LocalDateTime.now())) {
            row.setStatus(SkillInteractionStatus.EXPIRED);
            row.setUpdatedAt(LocalDateTime.now());
            skillInteractionMapper.updateById(row);
            return Optional.of(AgentResult.builder()
                    .success(false)
                    .answer("审批已超时，请重新发起流程")
                    .metadata(baseMeta(row, "timeout", "timeout"))
                    .build());
        }
        if (!SkillInteractionStatus.PENDING.equalsIgnoreCase(row.getStatus())) {
            return Optional.of(AgentResult.builder()
                    .success(false)
                    .answer("该审批已处理，无法重复提交")
                    .metadata(baseMeta(row, "closed", "closed"))
                    .build());
        }

        String action = action(request.getUiSubmit());
        if ("cancel".equals(action)) {
            row.setStatus(SkillInteractionStatus.CANCELLED);
            row.setUpdatedAt(LocalDateTime.now());
            skillInteractionMapper.updateById(row);
            return Optional.of(AgentResult.builder()
                    .success(true)
                    .answer("已取消审批")
                    .metadata(baseMeta(row, "cancelled", "cancelled"))
                    .build());
        }

        String route = isRejected(action, request.getUiSubmit()) ? "rejected" : "approved";
        Map<String, Object> approvalDocument = readMap(row.getSlotState());
        row.setStatus(SkillInteractionStatus.SUBMITTED);
        row.setUpdatedAt(LocalDateTime.now());
        row.setSlotState(writeJson(Map.of(
                "phase", "decision",
                "route", route,
                "decision", route,
                "submittedBy", request.getUserId() == null ? "" : request.getUserId(),
                "submittedAt", LocalDateTime.now().toString())));
        skillInteractionMapper.updateById(row);

        Optional<AgentResult> resumed = resumeGraphIfPossible(row, request, route, approvalDocument);
        if (resumed.isPresent()) {
            return resumed;
        }

        return Optional.of(AgentResult.builder()
                .success(true)
                .answer("approved".equals(route) ? "审批已通过" : "审批已拒绝")
                .metadata(baseMeta(row, route, route))
                .build());
    }

    private String action(UiSubmitPayload submit) {
        if (submit == null || submit.getAction() == null || submit.getAction().isBlank()) {
            return "approve";
        }
        return submit.getAction().trim().toLowerCase(Locale.ROOT);
    }

    private boolean isRejected(String action, UiSubmitPayload submit) {
        if ("reject".equals(action) || "rejected".equals(action)) {
            return true;
        }
        if (submit == null || submit.getValues() == null) {
            return false;
        }
        Object confirm = submit.getValues().get("confirm");
        if (confirm instanceof Boolean b) {
            return !b;
        }
        if (confirm != null) {
            return "false".equalsIgnoreCase(String.valueOf(confirm).trim());
        }
        return false;
    }

    private Optional<AgentResult> resumeGraphIfPossible(SkillInteractionEntity row,
                                                        ChatRequest request,
                                                        String route,
                                                        Map<String, Object> approvalDocument) {
        Object stateValue = approvalDocument.get("state");
        if (!(stateValue instanceof Map<?, ?> rawState)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> state = new LinkedHashMap<>((Map<String, Object>) rawState);
        String nodeId = row.getSkillName().substring(SKILL_PREFIX.length());

        GraphSpec graphSpec = readGraphSpec(approvalDocument.get("graphSpec"));
        GraphRuntimeContext runtimeContext = readRuntimeContext(approvalDocument.get("runtimeContext"));
        AgentDefinition definition = null;
        if (graphSpec == null || runtimeContext == null) {
            Object definitionValue = approvalDocument.get("definition");
            if (definitionValue == null) {
                return Optional.empty();
            }
            definition = objectMapper.convertValue(definitionValue, AgentDefinition.class);
            if (definition == null || definition.getGraphSpec() == null) {
                return Optional.empty();
            }
            graphSpec = definition.getGraphSpec();
            runtimeContext = GraphRuntimeContext.fromAgentDefinition(definition);
        }

        AgentRuntimeRequest.AgentRuntimeRequestBuilder runtimeRequestBuilder = AgentRuntimeRequest.builder()
                .traceId(row.getTraceId())
                .sessionId(row.getSessionId())
                .userId(request.getUserId())
                .message("")
                .graphSpec(graphSpec)
                .graphRuntimeContext(runtimeContext)
                .metadata(Map.of("resumedInteractionId", row.getId()));
        if (definition != null) {
            runtimeRequestBuilder.agentDefinition(definition);
        }
        AgentRuntimeRequest runtimeRequest = runtimeRequestBuilder.build();
        AgentRuntimeResult runtimeResult = langGraph4jRuntimeAdapter.resumeFromHumanApproval(
                runtimeRequest, definition, state, nodeId, route);
        Map<String, Object> metadata = new LinkedHashMap<>(runtimeResult.getMetadata() == null
                ? Map.of()
                : runtimeResult.getMetadata());
        metadata.putAll(baseMeta(row, route, route));
        metadata.put("resumed", true);
        mergeWorkflowMetadata(metadata, runtimeContext);
        return Optional.of(AgentResult.builder()
                .success(runtimeResult.isSuccess())
                .answer(runtimeResult.getAnswer())
                .steps(toAgentSteps(runtimeResult.getSteps()))
                .toolResults(runtimeResult.getArtifacts() == null ? Map.of() : runtimeResult.getArtifacts())
                .metadata(metadata)
                .uiRequest(runtimeResult.getUiRequest())
                .build());
    }

    private GraphSpec readGraphSpec(Object raw) {
        if (raw == null) {
            return null;
        }
        return objectMapper.convertValue(raw, GraphSpec.class);
    }

    private GraphRuntimeContext readRuntimeContext(Object raw) {
        if (raw == null) {
            return null;
        }
        return objectMapper.convertValue(raw, GraphRuntimeContext.class);
    }

    private void mergeWorkflowMetadata(Map<String, Object> metadata, GraphRuntimeContext runtimeContext) {
        if (metadata == null || runtimeContext == null) {
            return;
        }
        if (runtimeContext.getSourceType() != null) {
            metadata.putIfAbsent("sourceType", runtimeContext.getSourceType());
        }
        if (runtimeContext.getSourceId() != null) {
            metadata.putIfAbsent("sourceId", runtimeContext.getSourceId());
        }
        Map<String, Object> extra = runtimeContext.getExtra();
        if (extra != null) {
            metadata.putIfAbsent("workflowId", extra.get("workflowId"));
            metadata.putIfAbsent("workflowKeySlug", extra.get("workflowKeySlug"));
            metadata.putIfAbsent("workflowVersion", extra.get("workflowVersion"));
            metadata.putIfAbsent("workflowVersionId", extra.get("workflowVersionId"));
            metadata.putIfAbsent("entryAgentId", extra.get("entryAgentId"));
            metadata.putIfAbsent("entryAgentKeySlug", extra.get("entryAgentKeySlug"));
        }
    }

    private java.util.List<AgentResult.StepRecord> toAgentSteps(java.util.List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<AgentResult.StepRecord> out = new java.util.ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            out.add(new AgentResult.StepRecord("Step " + (i + 1), steps.get(i)));
        }
        return out;
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> baseMeta(SkillInteractionEntity row, String decision, String route) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("interactionId", row.getId());
        out.put("traceId", row.getTraceId());
        out.put("nodeId", row.getSkillName().substring(SKILL_PREFIX.length()));
        out.put("decision", decision);
        out.put("route", route);
        out.put("type", "humanApproval");
        return out;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
