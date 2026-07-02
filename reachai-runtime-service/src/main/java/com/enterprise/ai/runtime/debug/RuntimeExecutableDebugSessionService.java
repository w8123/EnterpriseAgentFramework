package com.enterprise.ai.runtime.debug;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeExecutableDebugSessionService {

    private static final String REQUEST_PARAMS = "__requestParams";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<MessageView>> MESSAGE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<RuntimeWorkflowDebugService.DebugStepResult>> STEP_LIST_TYPE =
            new TypeReference<>() {
            };

    private final RuntimeExecutableDebugSessionMapper mapper;
    private final RuntimeWorkflowDebugService workflowDebugService;
    private final ObjectMapper objectMapper;

    public SessionView create(CreateRequest request) {
        if (request == null || request.draftDefinition() == null || request.draftDefinition().isEmpty()) {
            throw new IllegalArgumentException("draftDefinition is required");
        }
        String sessionId = UUID.randomUUID().toString();
        String runId = "studio-debug-session-" + sessionId;
        Map<String, Object> options = new LinkedHashMap<>(request.debugOptions() == null ? Map.of() : request.debugOptions());
        options.put("runId", runId);
        options.put("traceId", runId);
        options.put("sessionId", sessionId);

        RuntimeWorkflowDebugService.DebugRunResult run = workflowDebugService.debugRun(debugRunRequest(
                request.targetType(),
                request.draftDefinition(),
                nullToEmpty(request.message()),
                request.inputParams() == null ? Map.of() : request.inputParams(),
                options));

        List<MessageView> messages = new ArrayList<>();
        if (StringUtils.hasText(request.message())) {
            messages.add(message("user", request.message(), null, run.traceId(), null));
        }
        appendRuntimeMessage(messages, run);

        LocalDateTime now = LocalDateTime.now();
        RuntimeExecutableDebugSessionEntity entity = new RuntimeExecutableDebugSessionEntity();
        entity.setId(sessionId);
        entity.setRunId(firstText(run.runId(), runId));
        entity.setTraceId(firstText(run.traceId(), runId));
        entity.setTargetType(firstText(request.targetType(), "WORKFLOW_DRAFT"));
        entity.setStatus(normalizeStatus(run.status()));
        entity.setCurrentNodeId(run.currentNodeId());
        entity.setDraftDefinitionJson(writeJson(request.draftDefinition()));
        entity.setDebugOptionsJson(writeJson(options));
        entity.setStateJson(writeJson(run.finalState()));
        entity.setMessagesJson(writeJson(messages));
        entity.setStepsJson(writeJson(run.steps()));
        entity.setUiRequestJson(writeJson(run.uiRequest()));
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setExpiresAt(now.plusHours(24));
        mapper.insert(entity);
        return toView(entity);
    }

    public SessionView get(String sessionId) {
        return toView(requireSession(sessionId));
    }

    public SessionView submit(String sessionId, SubmitRequest request) {
        RuntimeExecutableDebugSessionEntity entity = requireSession(sessionId);
        String status = normalizeStatus(entity.getStatus());
        if (!"WAITING".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("debug session is not waiting: " + sessionId);
        }
        if (!StringUtils.hasText(entity.getCurrentNodeId())) {
            throw new IllegalArgumentException("debug session has no waiting node: " + sessionId);
        }

        Map<String, Object> draft = readMap(entity.getDraftDefinitionJson());
        Map<String, Object> state = readMap(entity.getStateJson());
        Map<String, Object> submitted = request == null || request.values() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.values());
        Map<String, Object> submittedPayload = Map.of(
                "action", firstText(request == null ? null : request.action(), "submit"),
                "values", submitted);
        Map<String, Object> params = new LinkedHashMap<>(asMap(state.get(REQUEST_PARAMS)));
        params.putAll(submitted);
        state.put(REQUEST_PARAMS, params);
        state.put("params", params);
        state.put("submittedPayload", submittedPayload);
        state.put("values", submittedPayload);
        if (request != null && StringUtils.hasText(request.message())) {
            state.put("message", request.message());
            state.put("input", request.message());
        }

        Map<String, Object> options = readMap(entity.getDebugOptionsJson());
        options.put("entryNodeId", entity.getCurrentNodeId());
        options.put("runId", entity.getRunId());
        options.put("traceId", entity.getTraceId());
        options.put("sessionId", entity.getId());
        options.put("submittedPayload", submittedPayload);

        RuntimeWorkflowDebugService.DebugRunResult run = workflowDebugService.debugRun(debugRunRequest(
                entity.getTargetType(),
                draft,
                request == null ? "" : nullToEmpty(request.message()),
                state,
                options));

        List<MessageView> messages = readMessages(entity.getMessagesJson());
        messages.add(message("user", submitMessage(request, submitted), entity.getCurrentNodeId(), entity.getTraceId(), null));
        appendRuntimeMessage(messages, run);
        List<RuntimeWorkflowDebugService.DebugStepResult> steps = readSteps(entity.getStepsJson());
        steps.addAll(run.steps() == null ? List.of() : run.steps());

        entity.setStatus(normalizeStatus(run.status()));
        entity.setCurrentNodeId(run.currentNodeId());
        entity.setStateJson(writeJson(run.finalState()));
        entity.setMessagesJson(writeJson(messages));
        entity.setStepsJson(writeJson(steps));
        entity.setUiRequestJson(writeJson(run.uiRequest()));
        entity.setUpdateTime(LocalDateTime.now());
        mapper.updateById(entity);
        return toView(entity);
    }

    public SessionView cancel(String sessionId) {
        RuntimeExecutableDebugSessionEntity entity = requireSession(sessionId);
        entity.setStatus("CANCELLED");
        entity.setCurrentNodeId(null);
        entity.setUiRequestJson(writeJson(null));
        entity.setUpdateTime(LocalDateTime.now());
        List<MessageView> messages = readMessages(entity.getMessagesJson());
        messages.add(message("system", "debug session cancelled", null, entity.getTraceId(), null));
        entity.setMessagesJson(writeJson(messages));
        mapper.updateById(entity);
        return toView(entity);
    }

    private RuntimeWorkflowDebugService.DebugRunRequest debugRunRequest(String targetType,
                                                                       Map<String, Object> draft,
                                                                       String message,
                                                                       Map<String, Object> inputParams,
                                                                       Map<String, Object> debugOptions) {
        return new RuntimeWorkflowDebugService.DebugRunRequest(
                text(draft.get("workflowId")),
                text(draft.get("workflowKeySlug")),
                text(draft.get("workflowName")),
                text(draft.get("workflowType")),
                text(draft.get("projectCode")),
                text(draft.get("runtimeType")),
                text(draft.get("modelInstanceId")),
                graphSpecJson(draft),
                text(draft.get("canvasJson")),
                message,
                inputParams,
                debugOptions);
    }

    private String graphSpecJson(Map<String, Object> draft) {
        Object graphSpecJson = draft.get("graphSpecJson");
        if (graphSpecJson instanceof String text && StringUtils.hasText(text)) {
            return text;
        }
        Object graphSpec = draft.get("graphSpec");
        if (graphSpec instanceof String text && StringUtils.hasText(text)) {
            return text;
        }
        if (graphSpec != null) {
            return writeJson(graphSpec);
        }
        return null;
    }

    private void appendRuntimeMessage(List<MessageView> messages, RuntimeWorkflowDebugService.DebugRunResult run) {
        if (run == null) {
            return;
        }
        String status = normalizeStatus(run.status());
        if ("WAITING".equals(status)) {
            messages.add(message("assistant",
                    firstText(run.answer(), run.errorMessage(), "Waiting for user input"),
                    run.currentNodeId(),
                    run.traceId(),
                    run.uiRequest()));
            return;
        }
        messages.add(message(run.success() ? "assistant" : "system",
                firstText(run.answer(), run.errorMessage(), run.success() ? "debug completed" : "debug failed"),
                run.currentNodeId(),
                run.traceId(),
                null));
    }

    private SessionView toView(RuntimeExecutableDebugSessionEntity entity) {
        List<MessageView> messages = readMessages(entity.getMessagesJson());
        String status = normalizeStatus(entity.getStatus());
        return new SessionView(
                entity.getId(),
                entity.getRunId(),
                entity.getTraceId(),
                entity.getTargetType(),
                !"ERROR".equalsIgnoreCase(status) && !"CANCELLED".equalsIgnoreCase(status),
                status,
                entity.getCurrentNodeId(),
                lastAssistantContent(messages),
                messages,
                readSteps(entity.getStepsJson()),
                readMap(entity.getStateJson()),
                readObject(entity.getUiRequestJson()),
                entity.getCreateTime(),
                entity.getUpdateTime(),
                entity.getExpiresAt());
    }

    private RuntimeExecutableDebugSessionEntity requireSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId is required");
        }
        RuntimeExecutableDebugSessionEntity entity = mapper.selectById(sessionId.trim());
        if (entity == null) {
            throw new IllegalArgumentException("debug session not found: " + sessionId);
        }
        return entity;
    }

    private MessageView message(String role, String content, String nodeId, String traceId, Object uiRequest) {
        return new MessageView(
                UUID.randomUUID().toString(),
                role,
                nullToEmpty(content),
                nodeId,
                traceId,
                uiRequest,
                LocalDateTime.now());
    }

    private String submitMessage(SubmitRequest request, Map<String, Object> submitted) {
        if (request != null && StringUtils.hasText(request.message())) {
            return request.message();
        }
        return firstText(request == null ? null : request.action(), "submit") + " " + submitted;
    }

    private String lastAssistantContent(List<MessageView> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageView message = messages.get(i);
            if ("assistant".equalsIgnoreCase(message.role())) {
                return message.content();
            }
        }
        return "";
    }

    private List<MessageView> readMessages(String json) {
        return readList(json, MESSAGE_LIST_TYPE);
    }

    private List<RuntimeWorkflowDebugService.DebugStepResult> readSteps(String json) {
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

    private Object readObject(String json) {
        try {
            if (!StringUtils.hasText(json) || "null".equals(json)) {
                return null;
            }
            return objectMapper.readValue(json, Object.class);
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

    private static String normalizeStatus(String status) {
        if ("WAITING_USER".equalsIgnoreCase(status)) {
            return "WAITING";
        }
        return firstText(status, "ERROR").toUpperCase();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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

    public record CreateRequest(String targetType,
                                Map<String, Object> draftDefinition,
                                String message,
                                Map<String, Object> inputParams,
                                Map<String, Object> debugOptions) {
    }

    public record SubmitRequest(String action,
                                Map<String, Object> values,
                                String message) {
    }

    public record MessageView(String id,
                              String role,
                              String content,
                              String nodeId,
                              String traceId,
                              Object uiRequest,
                              LocalDateTime createdAt) {
    }

    public record SessionView(String sessionId,
                              String runId,
                              String traceId,
                              String targetType,
                              boolean success,
                              String status,
                              String currentNodeId,
                              String answer,
                              List<MessageView> messages,
                              List<RuntimeWorkflowDebugService.DebugStepResult> steps,
                              Map<String, Object> finalState,
                              Object uiRequest,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt,
                              LocalDateTime expiresAt) {
    }
}
