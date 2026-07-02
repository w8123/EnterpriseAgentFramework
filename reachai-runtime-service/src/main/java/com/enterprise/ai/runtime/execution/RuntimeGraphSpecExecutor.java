package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RuntimeGraphSpecExecutor {

    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    private static final int MAX_LINEAR_STEPS = 20;

    private final ObjectMapper objectMapper;
    private final RuntimeModelServiceClient modelServiceClient;
    private final RuntimeCapabilityCatalogClient capabilityClient;

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson, Map<String, Object> request) {
        return execute(graphSpecJson, request, null);
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId) {
        return execute(graphSpecJson, request, entryNodeId);
    }

    private RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                    Map<String, Object> request,
                                                    String entryOverride) {
        GraphSpec graph;
        try {
            graph = objectMapper.readValue(graphSpecJson, GraphSpec.class);
        } catch (Exception ex) {
            return failure("RUNTIME_WORKFLOW_GRAPH_INVALID", "Workflow GraphSpec JSON is invalid: " + ex.getMessage(),
                    null, null);
        }
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            return failure("RUNTIME_GRAPH_NODE_EMPTY", "GraphSpec requires at least one node", null, null);
        }
        String entry = firstText(text(entryOverride), text(graph.getEntry()));
        if (!StringUtils.hasText(entry)) {
            return failure("RUNTIME_GRAPH_ENTRY_MISSING", "GraphSpec entry is required", null, null);
        }

        Map<String, GraphSpec.Node> nodesById = new LinkedHashMap<>();
        for (GraphSpec.Node node : graph.getNodes()) {
            if (node != null && StringUtils.hasText(node.getId())) {
                nodesById.put(node.getId().trim(), node);
            }
        }
        if (!nodesById.containsKey(entry)) {
            return failure("RUNTIME_GRAPH_ENTRY_INVALID", "GraphSpec entry node does not exist: " + entry, entry, null);
        }

        Map<String, Object> context = initialContext(request == null ? Map.of() : request);
        List<Map<String, Object>> steps = new ArrayList<>();
        String currentNodeId = entry;
        RuntimeGraphSpecExecutionResult lastResult = null;
        for (int index = 0; index < MAX_LINEAR_STEPS && StringUtils.hasText(currentNodeId); index++) {
            GraphSpec.Node node = nodesById.get(currentNodeId);
            if (node == null) {
                return withSteps(failure("RUNTIME_GRAPH_NEXT_NODE_INVALID",
                        "GraphSpec next node does not exist: " + currentNodeId,
                        currentNodeId,
                        null), steps);
            }
            RuntimeGraphSpecExecutionResult nodeResult = executeNode(node, context);
            steps.addAll(nodeResult.steps());
            if (!nodeResult.success()) {
                return withSteps(nodeResult, steps);
            }
            lastResult = withSteps(nodeResult, steps);
            rememberNodeOutput(context, node, nodeResult.answer());
            if ("ANSWER".equals(nodeResult.nodeType())) {
                return lastResult;
            }
            currentNodeId = nextNodeId(graph, node.getId());
        }
        if (StringUtils.hasText(currentNodeId)) {
            return withSteps(failure("RUNTIME_GRAPH_STEP_LIMIT_EXCEEDED",
                    "Runtime GraphSpec linear execution exceeded step limit: " + MAX_LINEAR_STEPS,
                    currentNodeId,
                    null), steps);
        }
        return lastResult == null
                ? withSteps(failure("RUNTIME_GRAPH_ENTRY_MISSING", "GraphSpec entry is required", null, null), steps)
                : lastResult;
    }

    private RuntimeGraphSpecExecutionResult executeNode(GraphSpec.Node node, Map<String, Object> context) {
        String nodeType = AgentGraphNodeType.normalize(node.getType());
        return switch (nodeType) {
            case "USER_INPUT" -> executeUserInput(node, context);
            case "ANSWER" -> executeAnswer(node, context);
            case "LLM" -> executeLlm(node, context);
            case "TOOL", "CAPABILITY" -> executeTool(node, nodeType, context);
            case "INTERACTION" -> executeInteraction(node, context);
            default -> failure("RUNTIME_GRAPH_NODE_UNSUPPORTED",
                    "Runtime GraphSpec node type is not executable yet: " + nodeType,
                    node.getId(),
                    nodeType);
        };
    }

    private RuntimeGraphSpecExecutionResult executeUserInput(GraphSpec.Node node, Map<String, Object> context) {
        String input = firstText(text(context.get("input")), text(context.get("message")), "");
        Map<String, Object> metadata = nodeMetadata(node, "USER_INPUT");
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                input,
                node.getId(),
                "USER_INPUT",
                List.of(step("execute-node", node.getId())),
                metadata);
    }

    private RuntimeGraphSpecExecutionResult executeAnswer(GraphSpec.Node node, Map<String, Object> context) {
        String answer = renderAnswer(node, context);
        Map<String, Object> metadata = nodeMetadata(node, "ANSWER");
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                answer,
                node.getId(),
                "ANSWER",
                List.of(step("execute-node", node.getId())),
                metadata);
    }

    private String renderAnswer(GraphSpec.Node node, Map<String, Object> request) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String template = firstText(
                text(config.get("template")),
                text(config.get("answer")),
                text(config.get("content")),
                text(config.get("message")));
        if (!StringUtils.hasText(template)) {
            return firstText(text(request.get("message")), text(request.get("input")), "GraphSpec ANSWER node completed");
        }
        Matcher matcher = TEMPLATE_TOKEN.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(resolveToken(matcher.group(1), request)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private String resolveToken(String token, Map<String, Object> request) {
        return switch (token) {
            case "input", "message", "userInput", "query", "lastOutput", "previousOutput" ->
                    firstText(text(request.get(token)), text(request.get("message")), text(request.get("input")), "");
            default -> firstText(text(request.get(token)), "");
        };
    }

    private RuntimeGraphSpecExecutionResult executeLlm(GraphSpec.Node node, Map<String, Object> request) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String modelInstanceId = firstText(text(config.get("modelInstanceId")), text(request.get("modelInstanceId")));
        if (!StringUtils.hasText(modelInstanceId)) {
            return failure("RUNTIME_GRAPH_MODEL_REQUIRED",
                    "LLM node requires modelInstanceId on node config or request",
                    node.getId(),
                    "LLM");
        }
        ModelChatRequest modelRequest = ModelChatRequest.builder()
                .modelInstanceId(modelInstanceId)
                .messages(buildLlmMessages(config, request))
                .options(mapValue(firstPresent(config.get("modelParams"), config.get("options"))))
                .build();
        try {
            ModelChatResult result = modelServiceClient.chat(modelRequest);
            ModelChatData data = result == null ? null : result.getData();
            String answer = data == null ? null : text(data.getContent());
            if (!StringUtils.hasText(answer)) {
                return failure("RUNTIME_GRAPH_LLM_EMPTY",
                        "Model service returned empty content for LLM node: " + node.getId(),
                        node.getId(),
                        "LLM");
            }
            Map<String, Object> metadata = modelMetadata(node, data);
            return new RuntimeGraphSpecExecutionResult(
                    true,
                    "RUNTIME_GRAPH_EXECUTED",
                    answer,
                    node.getId(),
                    "LLM",
                    List.of(step("execute-node", node.getId())),
                    metadata);
        } catch (Exception ex) {
            return failure("RUNTIME_GRAPH_LLM_FAILED",
                    "LLM node execution failed: " + ex.getMessage(),
                    node.getId(),
                    "LLM");
        }
    }

    private RuntimeGraphSpecExecutionResult executeInteraction(GraphSpec.Node node, Map<String, Object> context) {
        Map<String, Object> submittedPayload = mapValue(firstPresent(context.get("submittedPayload"),
                firstPresent(context.get("values"), context.get("payload"))));
        if (submittedPayload == null || submittedPayload.isEmpty()) {
            return failure("RUNTIME_GRAPH_INTERACTION_WAITING",
                    "Interaction node is waiting for submitted payload: " + node.getId(),
                    node.getId(),
                    "INTERACTION");
        }
        context.put("submittedPayload", submittedPayload);
        context.put("values", submittedPayload);
        context.putAll(submittedPayload);
        Map<String, Object> metadata = nodeMetadata(node, "INTERACTION");
        metadata.put("submittedPayload", submittedPayload);
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                String.valueOf(submittedPayload),
                node.getId(),
                "INTERACTION",
                List.of(step("execute-node", node.getId())),
                metadata);
    }

    private RuntimeGraphSpecExecutionResult executeTool(GraphSpec.Node node,
                                                        String nodeType,
                                                        Map<String, Object> context) {
        String qualifiedName = resolveQualifiedName(node);
        if (!StringUtils.hasText(qualifiedName)) {
            return failure("RUNTIME_GRAPH_TOOL_REF_REQUIRED",
                    nodeType + " node requires ref.qualifiedName, config.qualifiedName, or config.ref",
                    node.getId(),
                    nodeType);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("input", buildToolInput(node, context));
        request.put("context", Map.of(
                "nodeId", node.getId(),
                "nodeType", nodeType));
        try {
            Map<String, Object> result = capabilityClient.executeTool(qualifiedName, request);
            Object output = firstPresent(result == null ? null : result.get("data"),
                    result == null ? null : result.get("result"));
            output = firstPresent(output, result == null ? null : result.get("body"));
            output = firstPresent(output, result);
            String answer = output == null ? "" : String.valueOf(output);
            Map<String, Object> metadata = nodeMetadata(node, nodeType);
            metadata.put("qualifiedName", qualifiedName);
            return new RuntimeGraphSpecExecutionResult(
                    true,
                    "RUNTIME_GRAPH_EXECUTED",
                    answer,
                    node.getId(),
                    nodeType,
                    List.of(step("execute-node", node.getId())),
                    metadata);
        } catch (Exception ex) {
            return failure("RUNTIME_GRAPH_TOOL_FAILED",
                    nodeType + " node execution failed: " + ex.getMessage(),
                    node.getId(),
                    nodeType);
        }
    }

    private Map<String, Object> buildToolInput(GraphSpec.Node node, Map<String, Object> context) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> mapping = mapValue(firstPresent(config.get("inputMapping"), config.get("args")));
        if (mapping == null || mapping.isEmpty()) {
            return Map.of("input", firstText(text(context.get("lastOutput")), text(context.get("input")), ""));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            input.put(entry.getKey(), renderTemplate(text(entry.getValue()), context));
        }
        return input;
    }

    private String resolveQualifiedName(GraphSpec.Node node) {
        if (node.getRef() != null) {
            String qualifiedName = firstText(
                    text(node.getRef().getQualifiedName()),
                    text(node.getRef().getName()));
            if (StringUtils.hasText(qualifiedName)) {
                return qualifiedName;
            }
        }
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        return firstText(text(config.get("qualifiedName")), text(config.get("ref")), text(config.get("toolName")));
    }

    private List<ChatMessage> buildLlmMessages(Map<String, Object> config, Map<String, Object> request) {
        List<ChatMessage> messages = new ArrayList<>();
        String systemPrompt = renderTemplate(text(config.get("systemPrompt")), request);
        if (StringUtils.hasText(systemPrompt)) {
            messages.add(ChatMessage.builder().role("system").content(systemPrompt).build());
        }

        Object configuredMessages = config.get("messages");
        if (configuredMessages instanceof List<?> items && !items.isEmpty()) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> message)) {
                    continue;
                }
                Object enabled = message.get("enabled");
                if (Boolean.FALSE.equals(enabled)) {
                    continue;
                }
                String role = firstText(text(message.get("role")), "user");
                String content = renderTemplate(text(message.get("content")), request);
                if (StringUtils.hasText(content)) {
                    messages.add(ChatMessage.builder().role(role).content(content).build());
                }
            }
        }

        if (messages.stream().noneMatch(message -> "user".equalsIgnoreCase(message.getRole()))) {
            String userPrompt = firstText(
                    renderTemplate(text(config.get("userPrompt")), request),
                    renderTemplate(text(config.get("prompt")), request),
                    text(request.get("message")),
                    text(request.get("input")));
            if (StringUtils.hasText(userPrompt)) {
                messages.add(ChatMessage.builder().role("user").content(userPrompt).build());
            }
        }
        return messages;
    }

    private String renderTemplate(String template, Map<String, Object> request) {
        if (!StringUtils.hasText(template)) {
            return null;
        }
        Matcher matcher = TEMPLATE_TOKEN.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(resolveToken(matcher.group(1), request)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private Map<String, Object> modelMetadata(GraphSpec.Node node, ModelChatData data) {
        Map<String, Object> metadata = nodeMetadata(node, "LLM");
        putIfPresent(metadata, "model", data.getModel());
        putIfPresent(metadata, "provider", data.getProvider());
        putIfPresent(metadata, "usage", data.getUsage());
        putIfPresent(metadata, "reasoningContent", data.getReasoningContent());
        putIfPresent(metadata, "toolCalls", data.getToolCalls());
        putIfPresent(metadata, "finishReason", data.getFinishReason());
        return metadata;
    }

    private Map<String, Object> nodeMetadata(GraphSpec.Node node, String nodeType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", node.getId());
        metadata.put("nodeType", nodeType);
        return metadata;
    }

    private Map<String, Object> initialContext(Map<String, Object> request) {
        Map<String, Object> context = new LinkedHashMap<>(request);
        String input = firstText(text(request.get("input")), text(request.get("message")), "");
        context.putIfAbsent("input", input);
        context.putIfAbsent("message", input);
        context.putIfAbsent("lastOutput", input);
        context.putIfAbsent("previousOutput", input);
        return context;
    }

    private void rememberNodeOutput(Map<String, Object> context, GraphSpec.Node node, String answer) {
        context.put("previousOutput", context.get("lastOutput"));
        context.put("lastOutput", firstText(answer, ""));
        context.put("nodeOutput." + node.getId(), firstText(answer, ""));
    }

    private String nextNodeId(GraphSpec graph, String nodeId) {
        if (graph.getEdges() == null || graph.getEdges().isEmpty()) {
            return null;
        }
        return graph.getEdges().stream()
                .filter(edge -> edge != null && nodeId.equals(text(edge.getFrom())))
                .filter(edge -> {
                    String condition = text(edge.getCondition());
                    return !StringUtils.hasText(condition) || "always".equalsIgnoreCase(condition)
                            || "success".equalsIgnoreCase(condition);
                })
                .sorted(Comparator.comparing(edge -> edge.getPriority() == null ? Integer.MAX_VALUE : edge.getPriority()))
                .map(GraphSpec.Edge::getTo)
                .filter(StringUtils::hasText)
                .filter(to -> !"END".equalsIgnoreCase(to))
                .findFirst()
                .orElse(null);
    }

    private RuntimeGraphSpecExecutionResult withSteps(RuntimeGraphSpecExecutionResult result,
                                                      List<Map<String, Object>> steps) {
        return new RuntimeGraphSpecExecutionResult(
                result.success(),
                result.code(),
                result.answer(),
                result.nodeId(),
                result.nodeType(),
                List.copyOf(steps),
                result.metadata());
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private Object firstPresent(Object first, Object fallback) {
        return first != null ? first : fallback;
    }

    private RuntimeGraphSpecExecutionResult failure(String code, String answer, String nodeId, String nodeType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.hasText(nodeId)) {
            metadata.put("nodeId", nodeId);
        }
        if (StringUtils.hasText(nodeType)) {
            metadata.put("nodeType", nodeType);
        }
        return new RuntimeGraphSpecExecutionResult(false, code, answer, nodeId, nodeType, List.of(), metadata);
    }

    private Map<String, Object> step(String name, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("detail", detail);
        return step;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
