package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.PageBridgeExecutionRequest;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.PageBridgeExecutionResponse;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RuntimeGraphSpecExecutor {

    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    private static final int MAX_LINEAR_STEPS = 20;
    private static final int DEFAULT_PAGE_BRIDGE_TIMEOUT_MS = 30_000;
    private static final Set<String> EXECUTABLE_NODE_TYPES = Set.of(
            "USER_INPUT",
            "INTENT_CLASSIFIER",
            "IF_ELSE",
            "ANSWER",
            "LLM",
            "TOOL",
            "CAPABILITY",
            "PAGE_ACTION",
            "INTERACTION",
            "PARAMETER_EXTRACT");

    private final ObjectMapper objectMapper;
    private final RuntimeModelServiceClient modelServiceClient;
    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeControlCatalogClient controlClient;

    public static boolean supportsNodeType(String rawType) {
        return EXECUTABLE_NODE_TYPES.contains(AgentGraphNodeType.normalize(rawType));
    }

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
            rememberNodeOutput(context, node, nodeResult);
            if ("ANSWER".equals(nodeResult.nodeType())) {
                return lastResult;
            }
            NextNodeResolution next = resolveNextNode(graph, node.getId(), nodeResult);
            String route = resultRoute(nodeResult);
            if (StringUtils.hasText(route) && !next.matched()) {
                return withSteps(failure(
                        "RUNTIME_GRAPH_ROUTE_UNRESOLVED",
                        nodeResult.nodeType() + " route has no matching outgoing edge: " + route,
                        node.getId(),
                        nodeResult.nodeType()), steps);
            }
            currentNodeId = next.nodeId();
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
            case "INTENT_CLASSIFIER" -> executeIntentClassifier(node, context);
            case "IF_ELSE" -> executeCondition(node, context);
            case "PARAMETER_EXTRACT" -> executeParameterExtract(node, context);
            case "ANSWER" -> executeAnswer(node, context);
            case "LLM" -> executeLlm(node, context);
            case "TOOL", "CAPABILITY" -> executeTool(node, nodeType, context);
            case "PAGE_ACTION" -> executePageAction(node, context);
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

    private RuntimeGraphSpecExecutionResult executeIntentClassifier(GraphSpec.Node node,
                                                                     Map<String, Object> context) {
        Map<String, Object> config = classifierConfig(node);
        String strategy = normalizeClassifierStrategy(text(config.get("strategy")));
        List<ClassifierClass> classes = classifierClasses(config.get("classes"));
        if (classes.isEmpty()) {
            return failure("RUNTIME_GRAPH_CLASSIFIER_CLASSES_REQUIRED",
                    "INTENT_CLASSIFIER requires at least one class",
                    node.getId(),
                    "INTENT_CLASSIFIER");
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
                        "INTENT_CLASSIFIER " + strategy + " strategy requires modelInstanceId on node config or request",
                        node.getId(),
                        "INTENT_CLASSIFIER");
            }
            try {
                decision = modelDecision(node, config, context, input, classes, defaultRoute, modelInstanceId);
            } catch (Exception ex) {
                return failure("RUNTIME_GRAPH_CLASSIFIER_FAILED",
                        "INTENT_CLASSIFIER model execution failed: " + ex.getMessage(),
                        node.getId(),
                        "INTENT_CLASSIFIER");
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
                true,
                "RUNTIME_GRAPH_EXECUTED",
                decision.route(),
                node.getId(),
                "INTENT_CLASSIFIER",
                List.of(classifierStep),
                metadata);
    }

    private RuntimeGraphSpecExecutionResult executeCondition(GraphSpec.Node node,
                                                              Map<String, Object> context) {
        Map<String, Object> config = conditionConfig(node);
        Object rawGroups = firstPresent(config.get("conditionGroups"), config.get("groups"));
        String route = null;
        if (rawGroups instanceof List<?> groups) {
            for (Object rawGroup : groups) {
                if (!(rawGroup instanceof Map<?, ?> group)) {
                    continue;
                }
                String groupId = text(group.get("id"));
                if (!StringUtils.hasText(groupId)) {
                    continue;
                }
                String logic = firstText(text(group.get("logic")), "AND");
                Object rawConditions = group.get("conditions");
                if (!(rawConditions instanceof List<?> conditions) || conditions.isEmpty()) {
                    continue;
                }
                boolean matched = "OR".equalsIgnoreCase(logic)
                        ? conditions.stream().anyMatch(condition -> evaluateCondition(condition, context))
                        : conditions.stream().allMatch(condition -> evaluateCondition(condition, context));
                if (matched) {
                    route = groupId;
                    break;
                }
            }
        }
        route = firstText(route, text(config.get("defaultRoute")), "else");
        context.put("route", route);
        context.put("lastRoute", route);
        Map<String, Object> metadata = nodeMetadata(node, "IF_ELSE");
        metadata.put("route", route);
        metadata.put("lastRoute", route);
        Map<String, Object> conditionStep = step("execute-node", node.getId());
        conditionStep.put("route", route);
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                route,
                node.getId(),
                "IF_ELSE",
                List.of(conditionStep),
                metadata);
    }

    private RuntimeGraphSpecExecutionResult executeParameterExtract(GraphSpec.Node node,
                                                                     Map<String, Object> context) {
        Map<String, Object> config = parameterConfig(node);
        String mode = "LLM".equalsIgnoreCase(firstText(
                text(config.get("extractMode")),
                text(config.get("mode")),
                "expression")) ? "LLM" : "EXPRESSION";
        List<Map<String, Object>> fields = parameterFields(config.get("fields"));
        if (fields.isEmpty()) {
            return failure("RUNTIME_GRAPH_PARAMETER_FIELDS_REQUIRED",
                    "PARAMETER_EXTRACT requires at least one target field",
                    node.getId(),
                    "PARAMETER_EXTRACT");
        }

        Map<String, Object> extracted;
        String modelOutput = null;
        if ("LLM".equals(mode)) {
            String modelInstanceId = resolveModelInstanceId(config, context);
            if (!StringUtils.hasText(modelInstanceId)) {
                return failure("RUNTIME_GRAPH_MODEL_REQUIRED",
                        "PARAMETER_EXTRACT LLM mode requires modelInstanceId on node config or Workflow/runtime context",
                        node.getId(),
                        "PARAMETER_EXTRACT");
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
                        renderTemplate(text(config.get("userPrompt")), context),
                        input);
                ModelChatResult result = modelServiceClient.chat(ModelChatRequest.builder()
                        .modelInstanceId(modelInstanceId)
                        .messages(List.of(
                                ChatMessage.builder().role("system").content(systemPrompt).build(),
                                ChatMessage.builder().role("user").content(userPrompt).build()))
                        .options(mapValue(firstPresent(config.get("modelParams"), config.get("options"))))
                        .build());
                ModelChatData data = result == null ? null : result.getData();
                modelOutput = data == null ? null : text(data.getContent());
                if (!StringUtils.hasText(modelOutput)) {
                    throw new IllegalStateException("model service returned empty parameter content for node " + node.getId());
                }
                extracted = normalizeExtractedFields(parseJsonObject(modelOutput), fields, context, false);
            } catch (IllegalArgumentException ex) {
                return failure("RUNTIME_GRAPH_PARAMETER_REQUIRED",
                        ex.getMessage(), node.getId(), "PARAMETER_EXTRACT");
            } catch (Exception ex) {
                return failure("RUNTIME_GRAPH_PARAMETER_EXTRACT_FAILED",
                        "PARAMETER_EXTRACT model execution failed: " + ex.getMessage(),
                        node.getId(),
                        "PARAMETER_EXTRACT");
            }
        } else {
            try {
                extracted = normalizeExtractedFields(Map.of(), fields, context, true);
            } catch (IllegalArgumentException ex) {
                return failure("RUNTIME_GRAPH_PARAMETER_REQUIRED",
                        ex.getMessage(), node.getId(), "PARAMETER_EXTRACT");
            }
        }

        String answer;
        try {
            answer = objectMapper.writeValueAsString(extracted);
        } catch (Exception ex) {
            return failure("RUNTIME_GRAPH_PARAMETER_EXTRACT_FAILED",
                    "PARAMETER_EXTRACT output serialization failed: " + ex.getMessage(),
                    node.getId(),
                    "PARAMETER_EXTRACT");
        }
        Map<String, Object> metadata = nodeMetadata(node, "PARAMETER_EXTRACT");
        metadata.put("mode", mode);
        metadata.put("structuredOutput", extracted);
        if (StringUtils.hasText(modelOutput)) {
            metadata.put("modelOutput", modelOutput);
        }
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                answer,
                node.getId(),
                "PARAMETER_EXTRACT",
                List.of(step("execute-node", node.getId())),
                metadata);
    }

    private Map<String, Object> parameterConfig(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("parameterConfig"));
        if (nested == null || nested.isEmpty()) {
            return config;
        }
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        config.forEach((key, value) -> {
            if (!"parameterConfig".equals(key)) {
                merged.put(key, value);
            }
        });
        return merged;
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
                value = resolveContextValue(source, context);
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
                        ? number.intValue()
                        : Integer.parseInt(String.valueOf(value).trim());
                case "number" -> value instanceof Number number
                        ? number.doubleValue()
                        : Double.parseDouble(String.valueOf(value).trim());
                case "boolean" -> value instanceof Boolean bool
                        ? bool
                        : Boolean.parseBoolean(String.valueOf(value).trim());
                default -> value;
            };
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("PARAMETER_EXTRACT field type conversion failed for "
                    + type + ": " + value);
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

    private Map<String, Object> conditionConfig(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("conditionConfig"));
        if (nested == null || nested.isEmpty()) {
            return config;
        }
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        config.forEach((key, value) -> {
            if (!"conditionConfig".equals(key)) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private boolean evaluateCondition(Object value, Map<String, Object> context) {
        if (!(value instanceof Map<?, ?> condition)) {
            return false;
        }
        Object left = resolveConditionOperand(condition.get("left"), context, true);
        Object right = resolveConditionOperand(condition.get("right"), context, false);
        String operator = firstText(text(condition.get("operator")), "equals").toLowerCase(Locale.ROOT);
        return switch (operator) {
            case "exists", "not_empty" -> !isEmptyValue(left);
            case "empty" -> isEmptyValue(left);
            case "equals", "eq" -> valuesEqual(left, right);
            case "not_equals", "neq" -> !valuesEqual(left, right);
            case "contains" -> containsValue(left, right);
            case "not_contains" -> !containsValue(left, right);
            case "gt" -> compareValues(left, right) > 0;
            case "gte" -> compareValues(left, right) >= 0;
            case "lt" -> compareValues(left, right) < 0;
            case "lte" -> compareValues(left, right) <= 0;
            default -> false;
        };
    }

    private Object resolveConditionOperand(Object value,
                                           Map<String, Object> context,
                                           boolean expressionByDefault) {
        if (!(value instanceof String raw)) {
            return value;
        }
        String candidate = raw.trim();
        if (candidate.contains("{{")) {
            return renderTemplate(candidate, context);
        }
        boolean looksLikeExpression = expressionByDefault
                || candidate.startsWith("$.")
                || candidate.startsWith("params.")
                || candidate.startsWith("nodeOutput.")
                || candidate.startsWith("state.")
                || context.containsKey(candidate);
        if (!looksLikeExpression) {
            return raw;
        }
        Object resolved = resolveContextValue(candidate, context);
        return resolved == null && !expressionByDefault ? raw : resolved;
    }

    private boolean isEmptyValue(Object value) {
        if (value == null) return true;
        if (value instanceof CharSequence text) return !StringUtils.hasText(text);
        if (value instanceof Collection<?> collection) return collection.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value) == 0;
        return false;
    }

    private boolean valuesEqual(Object left, Object right) {
        if (left == null || right == null) return left == right;
        Double leftNumber = numberValue(left);
        Double rightNumber = numberValue(right);
        if (leftNumber != null && rightNumber != null) {
            return Double.compare(leftNumber, rightNumber) == 0;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private boolean containsValue(Object left, Object right) {
        if (left == null || right == null) return false;
        if (left instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> valuesEqual(item, right));
        }
        if (left instanceof Map<?, ?> map) {
            return map.containsKey(right) || map.containsValue(right);
        }
        return String.valueOf(left).contains(String.valueOf(right));
    }

    private int compareValues(Object left, Object right) {
        Double leftNumber = numberValue(left);
        Double rightNumber = numberValue(right);
        if (leftNumber != null && rightNumber != null) {
            return Double.compare(leftNumber, rightNumber);
        }
        if (left == null || right == null) {
            return left == right ? 0 : left == null ? -1 : 1;
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private Double numberValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value != null) {
            try { return Double.parseDouble(String.valueOf(value).trim()); }
            catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private Map<String, Object> classifierConfig(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("classifierConfig"));
        if (nested == null || nested.isEmpty()) {
            return config;
        }
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        config.forEach((key, value) -> {
            if (!"classifierConfig".equals(key)) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private String classifierInput(String expression, Map<String, Object> context) {
        if (!StringUtils.hasText(expression)) {
            return firstText(text(context.get("input")), text(context.get("message")), "");
        }
        if (expression.contains("{{")) {
            return firstText(renderTemplate(expression, context), "");
        }
        Object value = resolveContextValue(expression, context);
        return value == null ? "" : String.valueOf(value);
    }

    private Object resolveContextValue(String expression, Map<String, Object> context) {
        String path = expression == null ? "" : expression.trim();
        if (path.startsWith("$.")) {
            path = path.substring(2);
        }
        Object value = context.get(path);
        if (value == null && ("query".equals(path) || "userInput".equals(path))) {
            value = firstPresent(context.get("input"), context.get("message"));
        }
        if (value == null && path.contains(".")) {
            Object current = context;
            for (String part : path.split("\\.")) {
                if (!(current instanceof Map<?, ?> map)) {
                    current = null;
                    break;
                }
                current = map.get(part);
            }
            value = current;
        }
        return value;
    }

    private ClassifierDecision keywordDecision(String input, List<ClassifierClass> classes) {
        String normalizedInput = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        ClassifierDecision best = null;
        int bestScore = -1;
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
                }
            }
        }
        return best;
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
                renderTemplate(text(config.get("llmPrompt")), promptContext),
                "Input: " + input);
        ModelChatRequest modelRequest = ModelChatRequest.builder()
                .modelInstanceId(modelInstanceId)
                .messages(List.of(
                        ChatMessage.builder().role("system").content(systemPrompt).build(),
                        ChatMessage.builder().role("user").content(userPrompt).build()))
                .options(mapValue(firstPresent(config.get("modelParams"), config.get("options"))))
                .build();
        ModelChatResult result = modelServiceClient.chat(modelRequest);
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
                        text(parsed.get("route")),
                        text(parsed.get("classId")),
                        text(parsed.get("id")),
                        text(parsed.get("intent")));
                return new ModelClassifierOutput(route, doubleValue(parsed.get("confidence"), 1D));
            } catch (Exception ignored) {
                // Fall through to the plain route form for tolerant model compatibility.
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
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .toList();
        }
        return List.of();
    }

    private String normalizeClassifierStrategy(String value) {
        String strategy = firstText(value, "KEYWORD").toUpperCase(Locale.ROOT);
        return "LLM".equals(strategy) || "HYBRID".equals(strategy) ? strategy : "KEYWORD";
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
            default -> firstText(text(resolveContextValue(token, request)), "");
        };
    }

    private RuntimeGraphSpecExecutionResult executeLlm(GraphSpec.Node node, Map<String, Object> request) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String modelInstanceId = resolveModelInstanceId(config, request);
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
        metadata.put("structuredOutput", submittedPayload);
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
        request.put("context", toolExecutionContext(node, nodeType, context));
        try {
            Map<String, Object> result = capabilityClient.executeTool(qualifiedName, request);
            Object output = firstPresent(result == null ? null : result.get("data"),
                    result == null ? null : result.get("result"));
            output = firstPresent(output, result == null ? null : result.get("body"));
            output = firstPresent(output, result);
            String answer = output == null ? "" : String.valueOf(output);
            Map<String, Object> metadata = nodeMetadata(node, nodeType);
            metadata.put("qualifiedName", qualifiedName);
            if (output != null) {
                metadata.put("structuredOutput", output);
            }
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

    private Map<String, Object> toolExecutionContext(GraphSpec.Node node,
                                                     String nodeType,
                                                     Map<String, Object> context) {
        Map<String, Object> executionContext = new LinkedHashMap<>();
        executionContext.put("nodeId", node.getId());
        executionContext.put("nodeType", nodeType);
        Map<String, Object> metadata = mapValue(context.get("metadata"));
        copyContextValue(executionContext, "tenantId", context, metadata);
        copyContextValue(executionContext, "externalUserId", context, metadata);
        copyContextValue(executionContext, "globalUserId", context, metadata);
        copyContextValue(executionContext, "userName", context, metadata);
        copyContextValue(executionContext, "deptId", context, metadata);
        copyContextValue(executionContext, "deptName", context, metadata);
        copyContextValue(executionContext, "roles", context, metadata);
        copyContextValue(executionContext, "agentId", context, metadata);
        copyContextValue(executionContext, "sessionId", context, metadata);
        copyContextValue(executionContext, "supervisorTraceId", context, metadata);
        copyContextValue(executionContext, "pageInstanceId", context, metadata);
        copyContextValue(executionContext, "origin", context, metadata);
        copyContextValue(executionContext, "route", context, metadata);
        if (!executionContext.containsKey("externalUserId") && context.get("userId") != null) {
            executionContext.put("externalUserId", context.get("userId"));
        }
        return executionContext;
    }

    private void copyContextValue(Map<String, Object> target,
                                  String key,
                                  Map<String, Object> context,
                                  Map<String, Object> metadata) {
        Object value = context.get(key);
        if (value == null && metadata != null) {
            value = metadata.get(key);
        }
        if (value != null) {
            target.put(key, value);
        }
    }

    private RuntimeGraphSpecExecutionResult executePageAction(GraphSpec.Node node,
                                                               Map<String, Object> context) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String sessionId = text(context.get("sessionId"));
        String projectCode = firstText(text(config.get("projectCode")), text(context.get("projectCode")));
        String agentId = text(context.get("agentId"));
        String targetPageKey = text(config.get("pageKey"));
        String actionKey = text(config.get("actionKey"));
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(projectCode)
                || !StringUtils.hasText(agentId) || !StringUtils.hasText(targetPageKey)
                || !StringUtils.hasText(actionKey)) {
            return failure("RUNTIME_PAGE_ACTION_CONTEXT_REQUIRED",
                    "PAGE_ACTION requires sessionId, projectCode, agentId, pageKey and actionKey",
                    node.getId(), "PAGE_ACTION");
        }
        Map<String, Object> args = buildToolInput(node, context);
        int timeoutMs = intValue(context.get("pageBridgeTimeoutMs"), DEFAULT_PAGE_BRIDGE_TIMEOUT_MS);
        try {
            PageBridgeExecutionResponse response = controlClient.executePageBridge(new PageBridgeExecutionRequest(
                    sessionId,
                    projectCode,
                    agentId,
                    text(context.get("pageKey")),
                    targetPageKey,
                    firstText(text(config.get("route")), text(config.get("routePattern"))),
                    actionKey,
                    args,
                    Boolean.TRUE.equals(config.get("confirm")) || Boolean.TRUE.equals(config.get("confirmRequired")),
                    timeoutMs));
            Map<String, Object> metadata = nodeMetadata(node, "PAGE_ACTION");
            metadata.put("pageKey", targetPageKey);
            metadata.put("actionKey", actionKey);
            if (response != null) {
                metadata.put("pageBridgeCode", response.code());
                metadata.put("pageBridgePhases", response.phases());
                metadata.put("pageBridgeData", response.data());
                if (response.data() != null) {
                    metadata.put("structuredOutput", response.data());
                }
            }
            if (response == null || !response.success()) {
                return new RuntimeGraphSpecExecutionResult(false,
                        response == null ? "RUNTIME_PAGE_ACTION_EMPTY" : response.code(),
                        response == null ? "Page Bridge returned no response" : response.status(),
                        node.getId(), "PAGE_ACTION", List.of(step("execute-page-action", node.getId())), metadata);
            }
            return new RuntimeGraphSpecExecutionResult(true, "RUNTIME_PAGE_ACTION_EXECUTED",
                    response.data() == null ? response.status() : String.valueOf(response.data()),
                    node.getId(), "PAGE_ACTION", List.of(step("execute-page-action", node.getId())), metadata);
        } catch (Exception ex) {
            return failure("RUNTIME_PAGE_ACTION_FAILED", "PAGE_ACTION failed: " + ex.getMessage(),
                    node.getId(), "PAGE_ACTION");
        }
    }

    private Map<String, Object> buildToolInput(GraphSpec.Node node, Map<String, Object> context) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> mapping = mapValue(firstPresent(config.get("inputMapping"), config.get("args")));
        if (mapping == null || mapping.isEmpty()) {
            String input = firstText(text(context.get("lastOutput")), text(context.get("input")));
            return Map.of("input", input == null ? "" : input);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            input.put(entry.getKey(), renderInputValue(entry.getValue(), context));
        }
        return input;
    }

    private Object renderInputValue(Object value, Map<String, Object> context) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> rendered.put(String.valueOf(key), renderInputValue(item, context)));
            return rendered;
        }
        if (value instanceof List<?> rawList) {
            return rawList.stream().map(item -> renderInputValue(item, context)).toList();
        }
        if (value instanceof String template) {
            if (!template.contains("{{") && isContextExpression(template, context)) {
                return resolveContextValue(template, context);
            }
            return renderTemplate(template, context);
        }
        return value;
    }

    private boolean isContextExpression(String value, Map<String, Object> context) {
        if (!StringUtils.hasText(value)) return false;
        String candidate = value.trim();
        if (context.containsKey(candidate)) return true;
        if (candidate.startsWith("$.")) candidate = candidate.substring(2);
        int separator = candidate.indexOf('.');
        String root = separator < 0 ? candidate : candidate.substring(0, separator);
        return context.get(root) instanceof Map<?, ?>
                || candidate.startsWith("params.")
                || candidate.startsWith("nodeOutput.")
                || "input".equals(candidate)
                || "message".equals(candidate)
                || "lastOutput".equals(candidate)
                || "previousOutput".equals(candidate);
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
        Map<String, Object> nested = mapValue(firstPresent(config.get("toolConfig"), config.get("capabilityConfig")));
        return firstText(
                text(config.get("qualifiedName")),
                configuredReference(config.get("ref")),
                text(config.get("toolName")),
                nested == null ? null : text(nested.get("qualifiedName")),
                nested == null ? null : configuredReference(nested.get("ref")),
                nested == null ? null : text(nested.get("toolName")));
    }

    private String configuredReference(Object rawReference) {
        Map<String, Object> reference = mapValue(rawReference);
        if (reference != null) {
            return firstText(
                    text(reference.get("qualifiedName")),
                    text(reference.get("name")),
                    reference.get("ref") instanceof Map<?, ?>
                            ? configuredReference(reference.get("ref"))
                            : text(reference.get("ref")),
                    text(reference.get("toolName")));
        }
        return text(rawReference);
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

    private void rememberNodeOutput(Map<String, Object> context,
                                    GraphSpec.Node node,
                                    RuntimeGraphSpecExecutionResult result) {
        String answer = result.answer();
        String nodeType = AgentGraphNodeType.normalize(node.getType());
        Object output = structuredOutput(result, answer);
        rememberOutputPath(context, "nodeOutput." + node.getId(), output);
        Map<String, Object> existingNodeOutputs = mapValue(context.get("nodeOutput"));
        Map<String, Object> nodeOutputs = existingNodeOutputs == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(existingNodeOutputs);
        nodeOutputs.put(node.getId(), output);
        context.put("nodeOutput", nodeOutputs);
        if ("INTENT_CLASSIFIER".equals(nodeType) || "IF_ELSE".equals(nodeType)) {
            return;
        }
        Object previousOutput = context.get("lastOutput");
        rememberOutputPath(context, "previousOutput", previousOutput);
        rememberOutputPath(context, "lastOutput", output);
    }

    private Object structuredOutput(RuntimeGraphSpecExecutionResult result, String answer) {
        if (result.metadata() != null && result.metadata().containsKey("structuredOutput")) {
            return result.metadata().get("structuredOutput");
        }
        return firstText(answer, "");
    }

    private void rememberOutputPath(Map<String, Object> context, String path, Object output) {
        context.keySet().removeIf(key -> key.startsWith(path + "."));
        context.put(path, output);
        flattenOutputPath(context, path, output);
    }

    private void flattenOutputPath(Map<String, Object> context, String path, Object output) {
        if (!(output instanceof Map<?, ?> map)) {
            return;
        }
        map.forEach((key, value) -> {
            String childPath = path + "." + key;
            context.put(childPath, value);
            flattenOutputPath(context, childPath, value);
        });
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

    private NextNodeResolution resolveNextNode(GraphSpec graph,
                                               String nodeId,
                                               RuntimeGraphSpecExecutionResult nodeResult) {
        if (graph.getEdges() == null || graph.getEdges().isEmpty()) {
            return NextNodeResolution.unmatched();
        }
        String route = resultRoute(nodeResult);
        String target = graph.getEdges().stream()
                .filter(edge -> edge != null && nodeId.equals(text(edge.getFrom())))
                .filter(edge -> edgeMatchRank(edge, route) < Integer.MAX_VALUE)
                .sorted(Comparator
                        .comparingInt((GraphSpec.Edge edge) -> edgeMatchRank(edge, route))
                        .thenComparing(edge -> edge.getPriority() == null ? Integer.MAX_VALUE : edge.getPriority()))
                .map(GraphSpec.Edge::getTo)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        if (!StringUtils.hasText(target)) {
            return NextNodeResolution.unmatched();
        }
        return "END".equalsIgnoreCase(target)
                ? NextNodeResolution.end()
                : NextNodeResolution.node(target);
    }

    private String resultRoute(RuntimeGraphSpecExecutionResult nodeResult) {
        return nodeResult == null || nodeResult.metadata() == null
                ? null
                : text(firstPresent(nodeResult.metadata().get("route"), nodeResult.metadata().get("lastRoute")));
    }

    private int edgeMatchRank(GraphSpec.Edge edge, String route) {
        String condition = text(edge.getCondition());
        boolean unconditional = !StringUtils.hasText(condition)
                || "always".equalsIgnoreCase(condition)
                || "success".equalsIgnoreCase(condition);
        if (StringUtils.hasText(route) && StringUtils.hasText(condition)) {
            String expectedRoute = condition.regionMatches(true, 0, "route:", 0, "route:".length())
                    ? condition.substring("route:".length()).trim()
                    : condition;
            if (route.equalsIgnoreCase(expectedRoute)) {
                return 0;
            }
            if (("else".equalsIgnoreCase(condition) || "default".equalsIgnoreCase(condition))
                    && ("else".equalsIgnoreCase(route) || "default".equalsIgnoreCase(route))) {
                return 0;
            }
        }
        return unconditional ? (StringUtils.hasText(route) ? 1 : 0) : Integer.MAX_VALUE;
    }

    private record NextNodeResolution(boolean matched, String nodeId) {
        private static NextNodeResolution unmatched() {
            return new NextNodeResolution(false, null);
        }

        private static NextNodeResolution end() {
            return new NextNodeResolution(true, null);
        }

        private static NextNodeResolution node(String nodeId) {
            return new NextNodeResolution(true, nodeId);
        }
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

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try { return Integer.parseInt(String.valueOf(value)); }
            catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value != null) {
            try { return Double.parseDouble(String.valueOf(value)); }
            catch (NumberFormatException ignored) { }
        }
        return fallback;
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
