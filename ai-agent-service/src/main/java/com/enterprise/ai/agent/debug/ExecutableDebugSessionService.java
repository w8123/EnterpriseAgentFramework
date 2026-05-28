package com.enterprise.ai.agent.debug;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import com.enterprise.ai.agent.runtime.LangGraph4jRuntimeAdapter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ExecutableDebugSessionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ExecutableDebugMessage>> MESSAGE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<LangGraph4jRuntimeAdapter.WorkflowDebugStepResult>> STEP_LIST_TYPE = new TypeReference<>() {
    };
    private static final String REQUEST_PARAMS = "__requestParams";

    private final ExecutableDebugSessionMapper sessionMapper;
    private final LangGraph4jRuntimeAdapter langGraph4jRuntimeAdapter;
    private final ObjectMapper objectMapper;

    public ExecutableDebugSessionService(ExecutableDebugSessionMapper sessionMapper,
                                         LangGraph4jRuntimeAdapter langGraph4jRuntimeAdapter,
                                         ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.langGraph4jRuntimeAdapter = langGraph4jRuntimeAdapter;
        this.objectMapper = objectMapper;
    }

    public ExecutableDebugSessionView create(CreateRequest request) {
        if (request == null || request.getDraftDefinition() == null) {
            throw new IllegalArgumentException("draftDefinition is required");
        }
        String sessionId = UUID.randomUUID().toString();
        String runId = "studio-debug-session-" + sessionId;
        AgentDefinition definition = toAgentDefinition(request.getTargetType(), request.getDraftDefinition());
        Map<String, Object> options = new LinkedHashMap<>(request.getDebugOptions() == null ? Map.of() : request.getDebugOptions());
        options.put("runId", runId);
        options.put("traceId", runId);
        options.put("sessionId", sessionId);

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult run = langGraph4jRuntimeAdapter.debugRun(
                definition,
                nullToEmpty(request.getMessage()),
                request.getInputParams() == null ? Map.of() : request.getInputParams(),
                options);

        List<ExecutableDebugMessage> messages = new ArrayList<>();
        if (StringUtils.hasText(request.getMessage())) {
            messages.add(message("user", request.getMessage(), null, run.getTraceId(), null));
        }
        appendRuntimeMessages(messages, run);

        LocalDateTime now = LocalDateTime.now();
        ExecutableDebugSessionEntity entity = new ExecutableDebugSessionEntity();
        entity.setId(sessionId);
        entity.setRunId(run.getRunId());
        entity.setTraceId(run.getTraceId());
        entity.setTargetType(firstNonBlank(request.getTargetType(), "AGENT_DRAFT"));
        entity.setStatus(run.getStatus());
        entity.setCurrentNodeId(run.getCurrentNodeId());
        entity.setDraftDefinitionJson(writeJson(request.getDraftDefinition()));
        entity.setDebugOptionsJson(writeJson(options));
        entity.setStateJson(writeJson(run.getFinalState()));
        entity.setMessagesJson(writeJson(messages));
        entity.setStepsJson(writeJson(run.getSteps()));
        entity.setUiRequestJson(writeJson(run.getUiRequest()));
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setExpiresAt(now.plusHours(24));
        sessionMapper.insert(entity);
        return toView(entity);
    }

    public ExecutableDebugSessionView submit(String sessionId, SubmitRequest request) {
        ExecutableDebugSessionEntity entity = requireSession(sessionId);
        if (!"WAITING".equalsIgnoreCase(entity.getStatus())) {
            throw new IllegalArgumentException("debug session is not waiting: " + sessionId);
        }
        if (!StringUtils.hasText(entity.getCurrentNodeId())) {
            throw new IllegalArgumentException("debug session has no waiting node: " + sessionId);
        }

        Map<String, Object> draft = readMap(entity.getDraftDefinitionJson());
        AgentDefinition definition = toAgentDefinition(entity.getTargetType(), draft);
        Map<String, Object> state = readMap(entity.getStateJson());
        Map<String, Object> submitted = request == null || request.getValues() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.getValues());
        Map<String, Object> params = new LinkedHashMap<>(asMap(state.get(REQUEST_PARAMS)));
        params.putAll(submitted);
        state.put(REQUEST_PARAMS, params);
        state.put("params", params);
        if (request != null && StringUtils.hasText(request.getMessage())) {
            state.put("input", request.getMessage());
            state.put("input.message", request.getMessage());
        }
        state.put("submittedPayload", Map.of(
                "action", firstNonBlank(request == null ? null : request.getAction(), "submit"),
                "values", submitted));

        Map<String, Object> options = readMap(entity.getDebugOptionsJson());
        options.put("state", state);
        options.put("entryNodeId", entity.getCurrentNodeId());
        options.put("runId", entity.getRunId());
        options.put("traceId", entity.getTraceId());
        options.put("sessionId", entity.getId());

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult run = langGraph4jRuntimeAdapter.debugRun(
                definition,
                request == null ? "" : nullToEmpty(request.getMessage()),
                params,
                options);

        List<ExecutableDebugMessage> messages = readMessages(entity.getMessagesJson());
        messages.add(message(
                "user",
                submitMessage(request, submitted),
                entity.getCurrentNodeId(),
                entity.getTraceId(),
                null));
        appendRuntimeMessages(messages, run);

        List<LangGraph4jRuntimeAdapter.WorkflowDebugStepResult> steps = readSteps(entity.getStepsJson());
        steps.addAll(run.getSteps() == null ? List.of() : run.getSteps());

        entity.setStatus(run.getStatus());
        entity.setCurrentNodeId(run.getCurrentNodeId());
        entity.setStateJson(writeJson(run.getFinalState()));
        entity.setMessagesJson(writeJson(messages));
        entity.setStepsJson(writeJson(steps));
        entity.setUiRequestJson(writeJson(run.getUiRequest()));
        entity.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(entity);
        return toView(entity);
    }

    public ExecutableDebugSessionView get(String sessionId) {
        return toView(requireSession(sessionId));
    }

    public ExecutableDebugSessionView cancel(String sessionId) {
        ExecutableDebugSessionEntity entity = requireSession(sessionId);
        entity.setStatus("CANCELLED");
        entity.setCurrentNodeId(null);
        entity.setUiRequestJson(writeJson(null));
        entity.setUpdateTime(LocalDateTime.now());
        List<ExecutableDebugMessage> messages = readMessages(entity.getMessagesJson());
        messages.add(message("system", "调试会话已取消", null, entity.getTraceId(), null));
        entity.setMessagesJson(writeJson(messages));
        sessionMapper.updateById(entity);
        return toView(entity);
    }

    private void appendRuntimeMessages(List<ExecutableDebugMessage> messages,
                                       LangGraph4jRuntimeAdapter.WorkflowDebugRunResult run) {
        if (run == null) {
            return;
        }
        for (LangGraph4jRuntimeAdapter.WorkflowDebugStepResult step : run.getSteps() == null ? List.<LangGraph4jRuntimeAdapter.WorkflowDebugStepResult>of() : run.getSteps()) {
            if (step.getUiRequest() != null && !"WAITING".equalsIgnoreCase(step.getStatus())) {
                messages.add(message("assistant",
                        firstNonBlank(step.getUiRequest().getMessage(), step.getUiRequest().getTitle(), "输出节点"),
                        step.getNodeId(),
                        run.getTraceId(),
                        step.getUiRequest()));
            }
        }
        if ("WAITING".equalsIgnoreCase(run.getStatus())) {
            UiRequestPayload uiRequest = run.getUiRequest();
            messages.add(message("assistant",
                    firstNonBlank(uiRequest == null ? null : uiRequest.getMessage(), run.getAnswer(), "需要补充信息"),
                    run.getCurrentNodeId(),
                    run.getTraceId(),
                    uiRequest));
            return;
        }
        messages.add(message(run.isSuccess() ? "assistant" : "system",
                firstNonBlank(run.getAnswer(), run.getErrorMessage(), run.isSuccess() ? "调试完成" : "调试失败"),
                run.getCurrentNodeId(),
                run.getTraceId(),
                null));
    }

    private ExecutableDebugSessionView toView(ExecutableDebugSessionEntity entity) {
        return ExecutableDebugSessionView.builder()
                .sessionId(entity.getId())
                .runId(entity.getRunId())
                .traceId(entity.getTraceId())
                .targetType(entity.getTargetType())
                .status(entity.getStatus())
                .success(!"ERROR".equalsIgnoreCase(entity.getStatus()) && !"CANCELLED".equalsIgnoreCase(entity.getStatus()))
                .currentNodeId(entity.getCurrentNodeId())
                .messages(readMessages(entity.getMessagesJson()))
                .steps(readSteps(entity.getStepsJson()))
                .finalState(readMap(entity.getStateJson()))
                .uiRequest(readValue(entity.getUiRequestJson(), UiRequestPayload.class))
                .createdAt(entity.getCreateTime())
                .updatedAt(entity.getUpdateTime())
                .expiresAt(entity.getExpiresAt())
                .answer(lastAssistantContent(entity.getMessagesJson()))
                .build();
    }

    private AgentDefinition toAgentDefinition(String targetType, Map<String, Object> draft) {
        if (!"COMPOSITION_DRAFT".equalsIgnoreCase(nullToEmpty(targetType))) {
            AgentDefinition definition = objectMapper.convertValue(draft, AgentDefinition.class);
            if (definition.getGraphSpec() == null) {
                definition.setGraphSpec(readGraphSpec(draft));
            }
            if (!StringUtils.hasText(definition.getId())) {
                definition.setId("studio-debug-draft");
            }
            if (!StringUtils.hasText(definition.getName())) {
                definition.setName("Studio Debug Draft");
            }
            return definition;
        }
        GraphSpec graphSpec = readGraphSpec(draft);
        return AgentDefinition.builder()
                .id(firstNonBlank(asString(draft.get("id")), asString(draft.get("qualifiedName")), "composition-debug-draft"))
                .keySlug(firstNonBlank(asString(draft.get("qualifiedName")), asString(draft.get("compositionCode")), "composition-debug-draft"))
                .name(firstNonBlank(asString(draft.get("name")), asString(draft.get("qualifiedName")), "Composition Debug Draft"))
                .intentType("WORKFLOW_DEBUG")
                .runtimeType("LANGGRAPH4J")
                .runtimePlacement("CENTRAL")
                .graphSpec(graphSpec)
                .maxSteps(64)
                .enabled(true)
                .type("single")
                .build();
    }

    private GraphSpec readGraphSpec(Map<String, Object> draft) {
        Object graphSpec = draft == null ? null : draft.get("graphSpec");
        if (graphSpec == null && draft != null) {
            graphSpec = draft.get("graphSpecJson");
        }
        if (graphSpec instanceof String text) {
            try {
                return objectMapper.readValue(text, GraphSpec.class);
            } catch (Exception ex) {
                throw new IllegalArgumentException("graphSpecJson parse failed: " + ex.getMessage(), ex);
            }
        }
        if (graphSpec != null) {
            return objectMapper.convertValue(graphSpec, GraphSpec.class);
        }
        throw new IllegalArgumentException("draftDefinition.graphSpec is required");
    }

    private ExecutableDebugSessionEntity requireSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId is required");
        }
        ExecutableDebugSessionEntity entity = sessionMapper.selectById(sessionId);
        if (entity == null) {
            throw new IllegalArgumentException("debug session not found: " + sessionId);
        }
        return entity;
    }

    private ExecutableDebugMessage message(String role, String content, String nodeId, String traceId, UiRequestPayload uiRequest) {
        return ExecutableDebugMessage.builder()
                .id(UUID.randomUUID().toString())
                .role(role)
                .content(nullToEmpty(content))
                .nodeId(nodeId)
                .traceId(traceId)
                .uiRequest(uiRequest)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private String submitMessage(SubmitRequest request, Map<String, Object> submitted) {
        if (request != null && StringUtils.hasText(request.getMessage())) {
            return request.getMessage();
        }
        String action = firstNonBlank(request == null ? null : request.getAction(), "submit");
        return action + " " + submitted;
    }

    private String lastAssistantContent(String json) {
        List<ExecutableDebugMessage> messages = readMessages(json);
        for (int i = messages.size() - 1; i >= 0; i--) {
            ExecutableDebugMessage message = messages.get(i);
            if ("assistant".equalsIgnoreCase(message.getRole())) {
                return message.getContent();
            }
        }
        return "";
    }

    private List<ExecutableDebugMessage> readMessages(String json) {
        return readList(json, MESSAGE_LIST_TYPE);
    }

    private List<LangGraph4jRuntimeAdapter.WorkflowDebugStepResult> readSteps(String json) {
        return readList(json, STEP_LIST_TYPE);
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        try {
            if (!StringUtils.hasText(json)) {
                return new ArrayList<>();
            }
            return new ArrayList<>(objectMapper.readValue(json, type));
        } catch (Exception ex) {
            throw new IllegalArgumentException("debug session json parse failed: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            if (!StringUtils.hasText(json)) {
                return new LinkedHashMap<>();
            }
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("debug session json parse failed: " + ex.getMessage(), ex);
        }
    }

    private <T> T readValue(String json, Class<T> type) {
        try {
            if (!StringUtils.hasText(json) || "null".equals(json)) {
                return null;
            }
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalArgumentException("debug session json parse failed: " + ex.getMessage(), ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("debug session json serialization failed: " + ex.getMessage(), ex);
        }
    }

    private static Map<String, Object> asMap(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> out.put(String.valueOf(key), item));
        }
        return out;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String targetType;
        private Map<String, Object> draftDefinition;
        private String message;
        private Map<String, Object> inputParams;
        private Map<String, Object> debugOptions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmitRequest {
        private String action;
        private Map<String, Object> values;
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecutableDebugSessionView {
        private String sessionId;
        private String runId;
        private String traceId;
        private String targetType;
        private boolean success;
        private String status;
        private String currentNodeId;
        private String answer;
        private List<ExecutableDebugMessage> messages;
        private List<LangGraph4jRuntimeAdapter.WorkflowDebugStepResult> steps;
        private Map<String, Object> finalState;
        private UiRequestPayload uiRequest;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime expiresAt;
    }
}
