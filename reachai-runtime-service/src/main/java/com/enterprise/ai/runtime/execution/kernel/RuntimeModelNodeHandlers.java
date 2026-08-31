package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Executes GraphSpec nodes whose business semantics are backed by the Model service. */
final class RuntimeModelNodeHandlers {

    private final ObjectMapper objectMapper;
    private final RuntimeModelServiceClient modelServiceClient;
    private final RuntimeNodeValueResolver valueResolver;

    RuntimeModelNodeHandlers(ObjectMapper objectMapper,
                             RuntimeModelServiceClient modelServiceClient,
                             RuntimeNodeValueResolver valueResolver) {
        this.objectMapper = objectMapper;
        this.modelServiceClient = modelServiceClient;
        this.valueResolver = valueResolver;
    }

    RuntimeGraphSpecExecutionResult executeIntentClassifier(GraphSpec.Node node,
                                                            RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        RuntimeGraphSpecExecutionCancellation cancel = execution.cancellation();
        Map<String, Object> config = mergedConfig(node, "classifierConfig");
        String strategy = normalizeClassifierStrategy(text(config.get("strategy")));
        List<ClassifierClass> classes = classifierClasses(config.get("classes"));
        if (classes.isEmpty()) {
            return failure("RUNTIME_GRAPH_CLASSIFIER_CLASSES_REQUIRED",
                    "INTENT_CLASSIFIER requires at least one class", node.getId(), "INTENT_CLASSIFIER");
        }

        String inputExpression = firstText(text(config.get("inputExpression")), "input");
        String input = classifierInput(inputExpression, context);
        String defaultRoute = firstText(text(config.get("defaultRoute")), "else");
        ClassifierDecision decision = null;
        if (!"LLM".equals(strategy)) {
            decision = keywordDecision(input, classes);
        }
        if (decision == null && !"KEYWORD".equals(strategy)) {
            String modelInstanceId = resolveModelInstanceId(config, context);
            if (!StringUtils.hasText(modelInstanceId)) {
                return failure("RUNTIME_GRAPH_MODEL_REQUIRED",
                        "INTENT_CLASSIFIER " + strategy
                                + " strategy requires modelInstanceId on node config or request",
                        node.getId(), "INTENT_CLASSIFIER");
            }
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "INTENT_CLASSIFIER");
            }
            try {
                // Feign sync chat is cooperatively cancelled at node boundaries; an in-flight
                // socket cannot be hard-interrupted by the current client stack.
                decision = modelDecision(node, config, context, input, classes, defaultRoute, modelInstanceId);
                if (cancel.isCancelled()) {
                    return cancelled(node.getId(), "INTENT_CLASSIFIER");
                }
            } catch (Exception ex) {
                if (cancel.isCancelled()) {
                    return cancelled(node.getId(), "INTENT_CLASSIFIER");
                }
                return failure("RUNTIME_GRAPH_CLASSIFIER_FAILED",
                        "INTENT_CLASSIFIER model execution failed: " + ex.getMessage(),
                        node.getId(), "INTENT_CLASSIFIER");
            }
        }
        if (decision == null) {
            decision = new ClassifierDecision(defaultRoute, 0D, "default", null);
        }

        context.put("route", decision.route());
        context.put("lastRoute", decision.route());
        Map<String, Object> metadata = nodeMetadata(node, "INTENT_CLASSIFIER");
        metadata.put("route", decision.route());
        metadata.put("lastRoute", decision.route());
        metadata.put("strategy", strategy);
        metadata.put("matchedBy", decision.matchedBy());
        metadata.put("confidence", decision.confidence());
        metadata.put("inputExpression", inputExpression);
        if (StringUtils.hasText(decision.modelOutput())) {
            metadata.put("modelOutput", decision.modelOutput());
        }
        Map<String, Object> classifierStep = step("execute-node", node.getId());
        classifierStep.put("route", decision.route());
        classifierStep.put("strategy", strategy);
        return new RuntimeGraphSpecExecutionResult(
                true, "RUNTIME_GRAPH_EXECUTED", decision.route(), node.getId(), "INTENT_CLASSIFIER",
                List.of(classifierStep), metadata);
    }

    RuntimeGraphSpecExecutionResult executeParameterExtract(GraphSpec.Node node,
                                                            RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        RuntimeGraphSpecExecutionCancellation cancel = execution.cancellation();
        Map<String, Object> config = mergedConfig(node, "parameterConfig");
        String mode = "LLM".equalsIgnoreCase(firstText(
                text(config.get("extractMode")), text(config.get("mode")), "expression"))
                ? "LLM" : "EXPRESSION";
        List<Map<String, Object>> fields = parameterFields(config.get("fields"));
        if (fields.isEmpty()) {
            return failure("RUNTIME_GRAPH_PARAMETER_FIELDS_REQUIRED",
                    "PARAMETER_EXTRACT requires at least one target field",
                    node.getId(), "PARAMETER_EXTRACT");
        }

        Map<String, Object> extracted;
        String modelOutput = null;
        if ("LLM".equals(mode)) {
            String modelInstanceId = resolveModelInstanceId(config, context);
            if (!StringUtils.hasText(modelInstanceId)) {
                return failure("RUNTIME_GRAPH_MODEL_REQUIRED",
                        "PARAMETER_EXTRACT LLM mode requires modelInstanceId on node config or Workflow/runtime context",
                        node.getId(), "PARAMETER_EXTRACT");
            }
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "PARAMETER_EXTRACT");
            }
            try {
                String inputExpression = firstText(text(config.get("inputExpression")), "input");
                String input = classifierInput(inputExpression, context);
                String fieldCatalog = objectMapper.writeValueAsString(fields);
                String systemPrompt = firstText(
                        text(config.get("systemPrompt")),
                        "Extract parameters from the user input according to this field catalog: " + fieldCatalog
                                + ". Return one JSON object only. Do not invent values that are absent.");
                String userPrompt = firstText(
                        valueResolver.renderTemplate(text(config.get("userPrompt")), context), input);
                ModelChatResult result = modelServiceClient.chat(ModelChatRequest.builder()
                        .modelInstanceId(modelInstanceId)
                        .messages(List.of(
                                ChatMessage.builder().role("system").content(systemPrompt).build(),
                                ChatMessage.builder().role("user").content(userPrompt).build()))
                        .options(mapValue(firstPresent(config.get("modelParams"), config.get("options"))))
                        .build());
                if (cancel.isCancelled()) {
                    return cancelled(node.getId(), "PARAMETER_EXTRACT");
                }
                ModelChatData data = result == null ? null : result.getData();
                modelOutput = data == null ? null : text(data.getContent());
                if (!StringUtils.hasText(modelOutput)) {
                    throw new IllegalStateException(
                            "model service returned empty parameter content for node " + node.getId());
                }
                extracted = normalizeExtractedFields(parseJsonObject(modelOutput), fields, context, false);
            } catch (IllegalArgumentException ex) {
                if (cancel.isCancelled()) {
                    return cancelled(node.getId(), "PARAMETER_EXTRACT");
                }
                return failure("RUNTIME_GRAPH_PARAMETER_REQUIRED",
                        ex.getMessage(), node.getId(), "PARAMETER_EXTRACT");
            } catch (Exception ex) {
                if (cancel.isCancelled()) {
                    return cancelled(node.getId(), "PARAMETER_EXTRACT");
                }
                return failure("RUNTIME_GRAPH_PARAMETER_EXTRACT_FAILED",
                        "PARAMETER_EXTRACT model execution failed: " + ex.getMessage(),
                        node.getId(), "PARAMETER_EXTRACT");
            }
        } else {
            try {
                extracted = normalizeExtractedFields(Map.of(), fields, context, true);
            } catch (IllegalArgumentException ex) {
                return failure("RUNTIME_GRAPH_PARAMETER_REQUIRED",
                        ex.getMessage(), node.getId(), "PARAMETER_EXTRACT");
            }
        }

        // Publish fields into the live context so later templates retain access after lastOutput changes.
        extracted.forEach(context::put);
        String answer;
        try {
            answer = objectMapper.writeValueAsString(extracted);
        } catch (Exception ex) {
            return failure("RUNTIME_GRAPH_PARAMETER_EXTRACT_FAILED",
                    "PARAMETER_EXTRACT output serialization failed: " + ex.getMessage(),
                    node.getId(), "PARAMETER_EXTRACT");
        }
        Map<String, Object> metadata = nodeMetadata(node, "PARAMETER_EXTRACT");
        metadata.put("mode", mode);
        metadata.put("structuredOutput", extracted);
        if (StringUtils.hasText(modelOutput)) {
            metadata.put("modelOutput", modelOutput);
        }
        return success(node, "PARAMETER_EXTRACT", answer, metadata);
    }

    RuntimeGraphSpecExecutionResult executeLlm(GraphSpec.Node node, RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        RuntimeGraphSpecExecutionCancellation cancel = execution.cancellation();
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String modelInstanceId = resolveModelInstanceId(config, context);
        if (!StringUtils.hasText(modelInstanceId)) {
            return failure("RUNTIME_GRAPH_MODEL_REQUIRED",
                    "LLM node requires modelInstanceId on node config or request", node.getId(), "LLM");
        }
        if (cancel.isCancelled()) {
            return cancelled(node.getId(), "LLM");
        }
        ModelChatRequest modelRequest = ModelChatRequest.builder()
                .modelInstanceId(modelInstanceId)
                .messages(buildLlmMessages(config, context))
                .options(mapValue(firstPresent(config.get("modelParams"), config.get("options"))))
                .build();
        boolean publicUserOutput = isSafePublicUserOutputLlm(node, execution.graph(), config);
        try {
            ModelChatResult result = modelServiceClient.chat(modelRequest);
            if (cancel.isCancelled()) {
                // Discard success after cancellation; never emit a public delta or success memory path.
                return cancelled(node.getId(), "LLM");
            }
            ModelChatData data = result == null ? null : result.getData();
            String answer = data == null ? null : text(data.getContent());
            if (!StringUtils.hasText(answer)) {
                return failure("RUNTIME_GRAPH_LLM_EMPTY",
                        "Model service returned empty content for LLM node: " + node.getId(),
                        node.getId(), "LLM");
            }
            RuntimeGraphSpecExecutionEventSink eventSink = execution.eventSink();
            if (publicUserOutput && eventSink != null) {
                Map<String, Object> deltaPayload = safeNodePayload(node, "LLM");
                deltaPayload.put("publicUserOutput", true);
                eventSink.onNodeDelta(node.getId(), "LLM", answer, deltaPayload);
            }
            Map<String, Object> metadata = modelMetadata(node, data);
            metadata.put("publicUserOutput", publicUserOutput);
            return success(node, "LLM", answer, metadata);
        } catch (Exception ex) {
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "LLM");
            }
            return failure("RUNTIME_GRAPH_LLM_FAILED",
                    "LLM node execution failed: " + ex.getMessage(), node.getId(), "LLM");
        }
    }

    private List<Map<String, Object>> parameterFields(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw) || !StringUtils.hasText(text(raw.get("name")))) {
                continue;
            }
            Map<String, Object> field = new LinkedHashMap<>();
            raw.forEach((key, fieldValue) -> field.put(String.valueOf(key), fieldValue));
            fields.add(field);
        }
        return fields;
    }

    private Map<String, Object> normalizeExtractedFields(Map<String, Object> modelValues,
                                                         List<Map<String, Object>> fields,
                                                         Map<String, Object> context,
                                                         boolean expressionMode) {
        Map<String, Object> extracted = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            String name = text(field.get("name"));
            Object value;
            if (expressionMode) {
                String source = firstText(text(field.get("source")), name);
                value = valueResolver.resolveContextValue(source, context);
            } else {
                value = modelValues.get(name);
            }
            if (value == null && field.containsKey("defaultValue")) {
                value = field.get("defaultValue");
            }
            value = convertParameterValue(value, text(field.get("type")));
            boolean required = Boolean.TRUE.equals(field.get("required"))
                    || "true".equalsIgnoreCase(text(field.get("required")));
            if (required && isEmptyValue(value)) {
                throw new IllegalArgumentException("PARAMETER_EXTRACT required field is missing: " + name);
            }
            if (value != null) {
                extracted.put(name, value);
            }
        }
        return extracted;
    }

    private Object convertParameterValue(Object value, String type) {
        if (value == null || !StringUtils.hasText(type)) return value;
        try {
            return switch (type.toLowerCase(Locale.ROOT)) {
                case "string" -> String.valueOf(value);
                case "integer" -> value instanceof Number number
                        ? number.intValue() : Integer.parseInt(String.valueOf(value).trim());
                case "number" -> value instanceof Number number
                        ? number.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
                case "boolean" -> value instanceof Boolean bool
                        ? bool : Boolean.parseBoolean(String.valueOf(value).trim());
                default -> value;
            };
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "PARAMETER_EXTRACT field type conversion failed for " + type + ": " + value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String content) {
        String candidate = content == null ? "" : content.trim();
        int objectStart = candidate.indexOf('{');
        int objectEnd = candidate.lastIndexOf('}');
        if (objectStart < 0 || objectEnd <= objectStart) {
            throw new IllegalArgumentException("model output is not a JSON object");
        }
        try {
            return objectMapper.readValue(candidate.substring(objectStart, objectEnd + 1), Map.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("model output JSON is invalid: " + ex.getMessage());
        }
    }

    private ClassifierDecision keywordDecision(String input, List<ClassifierClass> classes) {
        String normalizedInput = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        ClassifierDecision best = null;
        int bestScore = -1;
        boolean ambiguous = false;
        for (ClassifierClass candidate : classes) {
            for (String keyword : candidate.keywords()) {
                String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
                if (!StringUtils.hasText(normalizedKeyword) || !normalizedInput.contains(normalizedKeyword)) {
                    continue;
                }
                int score = normalizedKeyword.length();
                if (score > bestScore) {
                    bestScore = score;
                    best = new ClassifierDecision(candidate.id(), 1D, "keyword:" + keyword, null);
                    ambiguous = false;
                } else if (score == bestScore && best != null && !candidate.id().equals(best.route())) {
                    ambiguous = true;
                }
            }
        }
        return ambiguous ? null : best;
    }

    private ClassifierDecision modelDecision(GraphSpec.Node node,
                                               Map<String, Object> config,
                                               Map<String, Object> context,
                                               String input,
                                               List<ClassifierClass> classes,
                                               String defaultRoute,
                                               String modelInstanceId) throws Exception {
        List<Map<String, Object>> catalog = classes.stream()
                .map(item -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", item.id());
                    value.put("label", item.label());
                    value.put("description", item.description());
                    value.put("keywords", item.keywords());
                    return value;
                })
                .toList();
        String systemPrompt = "Classify the user input into exactly one route from this JSON catalog: "
                + objectMapper.writeValueAsString(catalog)
                + ". Return JSON only: {\"route\":\"<class id>\",\"confidence\":0.0}.";
        Map<String, Object> promptContext = new LinkedHashMap<>(context);
        promptContext.put("classifierInput", input);
        String userPrompt = firstText(
                valueResolver.renderTemplate(text(config.get("llmPrompt")), promptContext),
                "Input: " + input);
        ModelChatResult result = modelServiceClient.chat(ModelChatRequest.builder()
                .modelInstanceId(modelInstanceId)
                .messages(List.of(
                        ChatMessage.builder().role("system").content(systemPrompt).build(),
                        ChatMessage.builder().role("user").content(userPrompt).build()))
                .options(mapValue(firstPresent(config.get("modelParams"), config.get("options"))))
                .build());
        ModelChatData data = result == null ? null : result.getData();
        String output = data == null ? null : text(data.getContent());
        if (!StringUtils.hasText(output)) {
            throw new IllegalStateException("model service returned empty classifier content for node " + node.getId());
        }

        ModelClassifierOutput parsed = parseModelClassifierOutput(output);
        Set<String> allowedRoutes = new LinkedHashSet<>();
        classes.forEach(item -> allowedRoutes.add(item.id()));
        double threshold = Math.max(0D, Math.min(1D, doubleValue(config.get("confidenceThreshold"), 0.7D)));
        String proposedRoute = parsed.route();
        if (StringUtils.hasText(proposedRoute) && proposedRoute.startsWith("route:")) {
            proposedRoute = proposedRoute.substring("route:".length()).trim();
        }
        boolean accepted = StringUtils.hasText(proposedRoute)
                && allowedRoutes.contains(proposedRoute)
                && parsed.confidence() >= threshold;
        return new ClassifierDecision(
                accepted ? proposedRoute : defaultRoute,
                parsed.confidence(),
                accepted ? "llm" : "llm-default",
                output);
    }

    @SuppressWarnings("unchecked")
    private ModelClassifierOutput parseModelClassifierOutput(String output) {
        String candidate = output.trim();
        int objectStart = candidate.indexOf('{');
        int objectEnd = candidate.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(
                        candidate.substring(objectStart, objectEnd + 1), Map.class);
                String route = firstText(
                        text(parsed.get("route")), text(parsed.get("classId")),
                        text(parsed.get("id")), text(parsed.get("intent")));
                return new ModelClassifierOutput(route, doubleValue(parsed.get("confidence"), 1D));
            } catch (Exception ignored) {
                // Fall through to the tolerant plain-route form.
            }
        }
        String plainRoute = candidate.replace("`", "").replace("\"", "").trim();
        return new ModelClassifierOutput(plainRoute, 1D);
    }

    private List<ClassifierClass> classifierClasses(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<ClassifierClass> classes = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            String id = text(raw.get("id"));
            if (!StringUtils.hasText(id)) {
                continue;
            }
            classes.add(new ClassifierClass(
                    id,
                    firstText(text(raw.get("label")), id),
                    firstText(text(raw.get("description")), ""),
                    classifierKeywords(raw.get("keywords"))));
        }
        return classes;
    }

    private List<String> classifierKeywords(Object value) {
        if (value instanceof List<?> items) {
            return items.stream().map(this::text).filter(StringUtils::hasText).toList();
        }
        if (value instanceof String raw) {
            return Pattern.compile("[,，]").splitAsStream(raw)
                    .map(String::trim).filter(StringUtils::hasText).toList();
        }
        return List.of();
    }

    private String classifierInput(String expression, Map<String, Object> context) {
        if (!StringUtils.hasText(expression)) {
            return valueResolver.userInputText(context);
        }
        if (expression.contains("{{")) {
            return firstText(valueResolver.renderTemplate(expression, context), "");
        }
        if ("input".equals(expression.trim()) || "userInput".equals(expression.trim())
                || "query".equals(expression.trim())) {
            return valueResolver.userInputText(context);
        }
        Object value = valueResolver.resolveContextValue(expression, context);
        return value == null ? "" : String.valueOf(value);
    }

    private List<ChatMessage> buildLlmMessages(Map<String, Object> config, Map<String, Object> context) {
        List<ChatMessage> messages = new ArrayList<>();
        String systemPrompt = valueResolver.renderTemplate(text(config.get("systemPrompt")), context);
        if (StringUtils.hasText(systemPrompt)) {
            messages.add(ChatMessage.builder().role("system").content(systemPrompt).build());
        }
        Object configuredMessages = config.get("messages");
        if (configuredMessages instanceof List<?> items && !items.isEmpty()) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> message) || Boolean.FALSE.equals(message.get("enabled"))) {
                    continue;
                }
                String role = firstText(text(message.get("role")), "user");
                String content = valueResolver.renderTemplate(text(message.get("content")), context);
                if (StringUtils.hasText(content)) {
                    messages.add(ChatMessage.builder().role(role).content(content).build());
                }
            }
        }
        if (messages.stream().noneMatch(message -> "user".equalsIgnoreCase(message.getRole()))) {
            String userPrompt = firstText(
                    valueResolver.renderTemplate(text(config.get("userPrompt")), context),
                    valueResolver.renderTemplate(text(config.get("prompt")), context),
                    valueResolver.userInputText(context));
            if (StringUtils.hasText(userPrompt)) {
                messages.add(ChatMessage.builder().role("user").content(userPrompt).build());
            }
        }
        return messages;
    }

    private boolean isSafePublicUserOutputLlm(GraphSpec.Node node,
                                              GraphSpec graph,
                                              Map<String, Object> config) {
        if (config != null && Boolean.TRUE.equals(config.get("publicUserOutput"))) {
            return true;
        }
        if (graph == null || node == null || !StringUtils.hasText(node.getId())) {
            return false;
        }
        List<GraphSpec.Edge> outgoing = graph.getEdges() == null ? List.of() : graph.getEdges().stream()
                .filter(edge -> edge != null && node.getId().equals(text(edge.getFrom())))
                .toList();
        if (outgoing.isEmpty()) {
            return true;
        }
        if (outgoing.size() != 1) {
            return false;
        }
        String targetId = text(outgoing.get(0).getTo());
        GraphSpec.Node next = graph.getNodes() == null ? null : graph.getNodes().stream()
                .filter(candidate -> candidate != null && targetId != null && targetId.equals(candidate.getId()))
                .findFirst().orElse(null);
        return next != null
                && "ANSWER".equals(AgentGraphNodeType.normalize(next.getType()))
                && isPassthroughAnswer(next);
    }

    private boolean isPassthroughAnswer(GraphSpec.Node answerNode) {
        Map<String, Object> config = answerNode.getConfig() == null ? Map.of() : answerNode.getConfig();
        String template = firstText(
                text(config.get("template")), text(config.get("answer")),
                text(config.get("content")), text(config.get("message")));
        if (!StringUtils.hasText(template)) {
            return true;
        }
        String normalized = template.trim();
        return "{{lastOutput}}".equals(normalized)
                || "{{previousOutput}}".equals(normalized)
                || "{{last_output}}".equals(normalized)
                || "{{previous_output}}".equals(normalized);
    }

    private Map<String, Object> modelMetadata(GraphSpec.Node node, ModelChatData data) {
        Map<String, Object> metadata = nodeMetadata(node, "LLM");
        putIfPresent(metadata, "model", data.getModel());
        putIfPresent(metadata, "provider", data.getProvider());
        putIfPresent(metadata, "usage", data.getUsage());
        // Reasoning content itself must never enter metadata or public Trace fields.
        if (data.getReasoningContent() != null && !data.getReasoningContent().isEmpty()) {
            metadata.put("reasoningLength", data.getReasoningContent().length());
        }
        putIfPresent(metadata, "finishReason", data.getFinishReason());
        return metadata;
    }

    private String resolveModelInstanceId(Map<String, Object> config, Map<String, Object> context) {
        String nodeModelInstanceId = text(config.get("modelInstanceId"));
        if (StringUtils.hasText(nodeModelInstanceId)) {
            return nodeModelInstanceId;
        }
        if (context.containsKey("workflowDefaultModelInstanceId")) {
            return text(context.get("workflowDefaultModelInstanceId"));
        }
        return text(context.get("modelInstanceId"));
    }

    private Map<String, Object> mergedConfig(GraphSpec.Node node, String nestedKey) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get(nestedKey));
        if (nested == null || nested.isEmpty()) {
            return config;
        }
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        config.forEach((key, value) -> {
            if (!nestedKey.equals(key)) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private String normalizeClassifierStrategy(String value) {
        String strategy = firstText(value, "KEYWORD").toUpperCase(Locale.ROOT);
        return "LLM".equals(strategy) || "HYBRID".equals(strategy) ? strategy : "KEYWORD";
    }

    private boolean isEmptyValue(Object value) {
        if (value == null) return true;
        if (value instanceof CharSequence text) return !StringUtils.hasText(text);
        if (value instanceof Collection<?> collection) return collection.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value) == 0;
        return false;
    }

    private Map<String, Object> safeNodePayload(GraphSpec.Node node, String nodeType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", node.getId());
        payload.put("nodeType", nodeType);
        payload.put("nodeName", firstText(node.getName(), node.getId()));
        return payload;
    }

    private RuntimeGraphSpecExecutionResult success(GraphSpec.Node node,
                                                    String nodeType,
                                                    String answer,
                                                    Map<String, Object> metadata) {
        return new RuntimeGraphSpecExecutionResult(
                true, "RUNTIME_GRAPH_EXECUTED", answer, node.getId(), nodeType,
                List.of(step("execute-node", node.getId())), metadata);
    }

    private RuntimeGraphSpecExecutionResult cancelled(String nodeId, String nodeType) {
        return failure("RUNTIME_GRAPH_CANCELLED", "Workflow execution cancelled", nodeId, nodeType);
    }

    private RuntimeGraphSpecExecutionResult failure(String code, String answer, String nodeId, String nodeType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.hasText(nodeId)) metadata.put("nodeId", nodeId);
        if (StringUtils.hasText(nodeType)) metadata.put("nodeType", nodeType);
        return new RuntimeGraphSpecExecutionResult(false, code, answer, nodeId, nodeType, List.of(), metadata);
    }

    private Map<String, Object> nodeMetadata(GraphSpec.Node node, String nodeType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", node.getId());
        metadata.put("nodeType", nodeType);
        return metadata;
    }

    private Map<String, Object> step(String name, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("detail", detail);
        return step;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private Object firstPresent(Object first, Object fallback) {
        return first != null ? first : fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // Use fallback.
            }
        }
        return fallback;
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    private record ClassifierClass(String id,
                                   String label,
                                   String description,
                                   List<String> keywords) {
    }

    private record ClassifierDecision(String route,
                                      double confidence,
                                      String matchedBy,
                                      String modelOutput) {
    }

    private record ModelClassifierOutput(String route, double confidence) {
    }
}
