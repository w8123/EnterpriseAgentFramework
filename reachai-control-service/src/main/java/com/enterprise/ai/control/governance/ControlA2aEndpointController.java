package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ControlA2aEndpointController {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ControlA2aEndpointMapper endpointMapper;
    private final ControlA2aCallLogMapper callLogMapper;
    private final ControlA2aTaskMapper taskMapper;
    private final RuntimeProxyClient runtimeClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping(value = "/a2a/{agentKey}/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> agentCard(@PathVariable String agentKey) {
        long started = System.nanoTime();
        ControlA2aEndpointEntity endpoint = findEnabledEndpoint(agentKey);
        if (endpoint == null) {
            Map<String, Object> body = Map.of("error", "A2A endpoint not found: " + agentKey);
            recordCall(null, agentKey, null, "card", false, null, body,
                    "endpoint not found or disabled", null, started);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        Map<String, Object> card = new LinkedHashMap<>(parseCard(endpoint.getCardJson()));
        card.putIfAbsent("url", "/a2a/" + endpoint.getAgentKey() + "/jsonrpc");
        recordCall(endpoint, endpoint.getAgentKey(), null, "card", true, null, card,
                null, null, started);
        return ResponseEntity.ok(card);
    }

    @PostMapping(value = "/a2a/{agentKey}/jsonrpc", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jsonRpc(@PathVariable String agentKey,
                                                       @RequestBody(required = false) Map<String, Object> request) {
        long started = System.nanoTime();
        Object id = request == null ? null : request.get("id");
        String method = text(request == null ? null : request.get("method"));
        ControlA2aEndpointEntity endpoint = findEnabledEndpoint(agentKey);
        if (endpoint == null) {
            Map<String, Object> response = error(id, -32004, "A2A endpoint not found: " + agentKey);
            recordCall(null, agentKey, null, firstText(method, "unknown"), false, request, response,
                    "endpoint not found or disabled", null, started);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        if (!StringUtils.hasText(method)) {
            Map<String, Object> response = error(id, -32600, "missing method");
            recordCall(endpoint, endpoint.getAgentKey(), null, "unknown", false, request, response,
                    "missing method", null, started);
            return ResponseEntity.ok(response);
        }

        try {
            Map<String, Object> response = switch (method) {
                case "message/send" -> handleMessageSend(endpoint, id, mapValue(request.get("params")));
                case "tasks/get" -> handleTaskGet(id, mapValue(request.get("params")));
                case "tasks/cancel" -> handleTaskCancel(id, mapValue(request.get("params")));
                default -> error(id, -32601, "method not found: " + method);
            };
            boolean success = !response.containsKey("error");
            String taskId = taskId(response);
            String traceId = traceId(response);
            recordCall(endpoint, endpoint.getAgentKey(), taskId, method, success, request, response,
                    success ? null : errorMessage(response), traceId, started);
            return ResponseEntity.ok(response);
        } catch (RuntimeException ex) {
            Map<String, Object> response = error(id, -32603, "internal error: " + ex.getMessage());
            recordCall(endpoint, endpoint.getAgentKey(), null, method, false, request, response,
                    ex.getMessage(), null, started);
            return ResponseEntity.ok(response);
        }
    }

    private Map<String, Object> handleMessageSend(ControlA2aEndpointEntity endpoint,
                                                  Object id,
                                                  Map<String, Object> params) {
        String contextId = firstText(text(params.get("contextId")), newId());
        String userId = firstText(text(mapValue(params.get("metadata")).get("userId")), "a2a-anonymous");
        String userText = extractText(mapValue(params.get("message")));
        String taskId = newId();
        ControlA2aTaskEntity taskEntity = createWorkingTask(endpoint, taskId, contextId, userId, params.get("message"));

        Map<String, Object> runtimeBody = new LinkedHashMap<>();
        runtimeBody.put("agentDefinitionId", endpoint.getAgentId());
        runtimeBody.put("sessionId", contextId);
        runtimeBody.put("userId", userId);
        runtimeBody.put("message", userText);
        runtimeBody.put("projectCode", endpoint.getProjectCode());
        runtimeBody.put("intentHint", "A2A_MESSAGE_SEND");
        runtimeBody.put("metadata", Map.of(
                "agentKey", endpoint.getAgentKey(),
                "endpointId", endpoint.getId(),
                "tenantId", nullToEmpty(endpoint.getTenantId()),
                "environment", nullToEmpty(endpoint.getEnvironment())));
        ResponseEntity<Map<String, Object>> runtimeResponse = runtimeClient.executeAgent(runtimeBody);
        if (!runtimeResponse.getStatusCode().is2xxSuccessful() || runtimeResponse.getBody() == null) {
            failTask(taskEntity, "runtime agent execution failed");
            throw new IllegalStateException("runtime agent execution failed");
        }

        Map<String, Object> body = runtimeResponse.getBody();
        String answer = firstText(text(body.get("answer")), "");
        Map<String, Object> task = buildTask(taskId, contextId, endpoint.getAgentKey(), userText, answer, mapValue(body.get("metadata")));
        completeTask(taskEntity, task, text(mapValue(body.get("metadata")).get("traceId")));
        return success(id, task);
    }

    private Map<String, Object> handleTaskGet(Object id, Map<String, Object> params) {
        String taskId = text(params.get("id"));
        ControlA2aTaskEntity task = findTask(taskId);
        if (task == null) {
            return error(id, -32004, "task not found: " + taskId);
        }
        Map<String, Object> output = parseTaskOutput(task.getOutputTaskJson());
        return success(id, output.isEmpty() ? taskStatus(task) : output);
    }

    private Map<String, Object> handleTaskCancel(Object id, Map<String, Object> params) {
        String taskId = text(params.get("id"));
        ControlA2aTaskEntity task = findTask(taskId);
        if (task == null || isFinished(task.getState())) {
            return error(id, -32005, "task cannot be canceled or not found: " + taskId);
        }
        LocalDateTime now = LocalDateTime.now();
        task.setState("canceled");
        task.setCompletedAt(now);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
        return success(id, taskStatus(task));
    }

    private Map<String, Object> buildTask(String taskId,
                                          String contextId,
                                          String agentKey,
                                          String userText,
                                          String answer,
                                          Map<String, Object> runtimeMetadata) {
        Map<String, Object> userMessage = message("user", userText);
        Map<String, Object> agentMessage = message("agent", answer);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", "completed");
        status.put("message", agentMessage);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentKey", agentKey);
        metadata.putAll(runtimeMetadata);

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", taskId);
        task.put("kind", "task");
        task.put("contextId", contextId);
        task.put("status", status);
        task.put("history", List.of(userMessage, agentMessage));
        task.put("metadata", metadata);
        return task;
    }

    private Map<String, Object> taskStatus(ControlA2aTaskEntity entity) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", firstText(entity.getState(), "unknown"));
        if (StringUtils.hasText(entity.getErrorMessage())) {
            status.put("message", message("agent", entity.getErrorMessage()));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentKey", entity.getAgentKey());
        if (StringUtils.hasText(entity.getTraceId())) {
            metadata.put("traceId", entity.getTraceId());
        }
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", entity.getTaskId());
        task.put("kind", "task");
        task.put("contextId", entity.getContextId());
        task.put("status", status);
        task.put("metadata", metadata);
        return task;
    }

    private Map<String, Object> message(String role, String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("messageId", newId());
        message.put("parts", List.of(Map.of(
                "kind", "text",
                "text", text == null ? "" : text)));
        return message;
    }

    private ControlA2aEndpointEntity findEnabledEndpoint(String agentKey) {
        if (!StringUtils.hasText(agentKey)) {
            return null;
        }
        ControlA2aEndpointEntity endpoint = endpointMapper.selectOne(new LambdaQueryWrapper<ControlA2aEndpointEntity>()
                .eq(ControlA2aEndpointEntity::getAgentKey, agentKey)
                .last("limit 1"));
        return endpoint != null && Boolean.TRUE.equals(endpoint.getEnabled()) ? endpoint : null;
    }

    private ControlA2aTaskEntity findTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return null;
        }
        return taskMapper.selectOne(new LambdaQueryWrapper<ControlA2aTaskEntity>()
                .eq(ControlA2aTaskEntity::getTaskId, taskId)
                .last("limit 1"));
    }

    private ControlA2aTaskEntity createWorkingTask(ControlA2aEndpointEntity endpoint,
                                                   String taskId,
                                                   String contextId,
                                                   String userId,
                                                   Object inputMessage) {
        LocalDateTime now = LocalDateTime.now();
        ControlA2aTaskEntity entity = new ControlA2aTaskEntity();
        entity.setTaskId(taskId);
        entity.setEndpointId(endpoint.getId());
        entity.setAgentKey(endpoint.getAgentKey());
        entity.setContextId(contextId);
        entity.setUserId(userId);
        entity.setState("working");
        entity.setInputMessageJson(toJson(inputMessage));
        entity.setStartedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        taskMapper.insert(entity);
        return entity;
    }

    private void completeTask(ControlA2aTaskEntity entity, Map<String, Object> task, String traceId) {
        LocalDateTime now = LocalDateTime.now();
        entity.setState("completed");
        entity.setOutputTaskJson(toJson(task));
        entity.setTraceId(traceId);
        entity.setCompletedAt(now);
        entity.setUpdatedAt(now);
        taskMapper.updateById(entity);
    }

    private void failTask(ControlA2aTaskEntity entity, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        entity.setState("failed");
        entity.setErrorMessage(errorMessage);
        entity.setCompletedAt(now);
        entity.setUpdatedAt(now);
        taskMapper.updateById(entity);
    }

    private Map<String, Object> parseCard(String cardJson) {
        if (!StringUtils.hasText(cardJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(cardJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return new LinkedHashMap<>(Map.of("raw", cardJson));
        }
    }

    private Map<String, Object> parseTaskOutput(String outputTaskJson) {
        if (!StringUtils.hasText(outputTaskJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(outputTaskJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private void recordCall(ControlA2aEndpointEntity endpoint,
                            String agentKey,
                            String taskId,
                            String method,
                            boolean success,
                            Object request,
                            Object response,
                            String errorMessage,
                            String traceId,
                            long started) {
        ControlA2aCallLogEntity log = new ControlA2aCallLogEntity();
        if (endpoint != null) {
            log.setEndpointId(endpoint.getId());
            log.setProjectId(endpoint.getProjectId());
            log.setProjectCode(endpoint.getProjectCode());
            log.setEnvironment(endpoint.getEnvironment());
            log.setTenantId(endpoint.getTenantId());
        }
        log.setAgentKey(agentKey);
        log.setTaskId(taskId);
        log.setMethod(method);
        log.setSuccess(success);
        log.setLatencyMs((System.nanoTime() - started) / 1_000_000L);
        log.setRequestBody(toJson(request));
        log.setResponseBody(toJson(response));
        log.setErrorMessage(errorMessage);
        log.setTraceId(traceId);
        log.setCreatedAt(LocalDateTime.now());
        callLogMapper.insert(log);
    }

    private Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", StringUtils.hasText(message) ? message : "A2A request failed");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);
        return response;
    }

    private String extractText(Map<String, Object> message) {
        Object parts = message.get("parts");
        if (!(parts instanceof Iterable<?> iterable)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Object item : iterable) {
            Map<String, Object> part = mapValue(item);
            if ("text".equals(text(part.get("kind"))) && StringUtils.hasText(text(part.get("text")))) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(text(part.get("text")));
            }
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String taskId(Map<String, Object> response) {
        return text(mapValue(response.get("result")).get("id"));
    }

    private String traceId(Map<String, Object> response) {
        return text(mapValue(mapValue(response.get("result")).get("metadata")).get("traceId"));
    }

    private String errorMessage(Map<String, Object> response) {
        return text(mapValue(response.get("error")).get("message"));
    }

    private boolean isFinished(String state) {
        return "completed".equals(state) || "failed".equals(state) || "canceled".equals(state);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private String newId() {
        return UUID.randomUUID().toString();
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
