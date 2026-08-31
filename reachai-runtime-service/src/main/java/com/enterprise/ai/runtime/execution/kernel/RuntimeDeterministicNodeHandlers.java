package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.graph.GraphSpecUserInputContract;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.context.WorkflowOutputAliasWriter;
import com.enterprise.ai.runtime.execution.context.WorkflowVariableNamespaces;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic GraphSpec node semantics.
 *
 * <p>These handlers do not call models, capabilities, browsers, or remote services. Keeping them
 * outside the orchestration engine makes their context mutations and outputs independently
 * testable while the registry remains the executable node-type truth source.</p>
 */
final class RuntimeDeterministicNodeHandlers {

    private final ObjectMapper objectMapper;
    private final RuntimeNodeValueResolver valueResolver;

    RuntimeDeterministicNodeHandlers(ObjectMapper objectMapper, RuntimeNodeValueResolver valueResolver) {
        this.objectMapper = objectMapper;
        this.valueResolver = valueResolver;
    }

    RuntimeGraphSpecExecutionResult executeUserInput(GraphSpec.Node node, RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        String input = valueResolver.userInputText(context);
        writeUserInputParams(node, context, input);
        return success(node, "USER_INPUT", input, nodeMetadata(node, "USER_INPUT"));
    }

    RuntimeGraphSpecExecutionResult executeCondition(GraphSpec.Node node, RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
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

    RuntimeGraphSpecExecutionResult executeAnswer(GraphSpec.Node node, RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String template = firstText(
                text(config.get("template")),
                text(config.get("answer")),
                text(config.get("content")),
                text(config.get("message")));
        String answer = !StringUtils.hasText(template)
                ? firstText(text(context.get("message")), text(context.get("input")),
                "GraphSpec ANSWER node completed")
                : valueResolver.renderTemplate(template, context);
        return success(node, "ANSWER", answer, nodeMetadata(node, "ANSWER"));
    }

    RuntimeGraphSpecExecutionResult executeVariableAssign(GraphSpec.Node node,
                                                          RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> assignments = mapValue(config.get("assignments"));
        if (assignments == null || assignments.isEmpty()) {
            return failure("RUNTIME_GRAPH_ASSIGNMENTS_REQUIRED",
                    "VARIABLE_ASSIGN requires non-empty assignments", node.getId(), "VARIABLE_ASSIGN");
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        Map<String, String> normalizedTargets = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Object> entry : assignments.entrySet()) {
                String normalized = WorkflowVariableNamespaces.normalizeBusinessWriteTarget(entry.getKey());
                Object value = valueResolver.renderInputValue(entry.getValue(), context);
                resolved.put(normalized, value);
                normalizedTargets.put(entry.getKey(), normalized);
            }
            for (Map.Entry<String, Object> entry : resolved.entrySet()) {
                WorkflowOutputAliasWriter.writeBusinessPath(context, entry.getKey(), entry.getValue());
            }
        } catch (IllegalArgumentException ex) {
            return failure("RUNTIME_GRAPH_ASSIGNMENT_INVALID",
                    "VARIABLE_ASSIGN failed for node " + node.getId() + ": " + ex.getMessage(),
                    node.getId(), "VARIABLE_ASSIGN");
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("assignments", resolved);
        structured.put("targets", normalizedTargets);
        Map<String, Object> metadata = nodeMetadata(node, "VARIABLE_ASSIGN");
        metadata.put("structuredOutput", structured);
        metadata.put("traceSummary", Map.of("assignmentCount", resolved.size()));
        return success(node, "VARIABLE_ASSIGN", serialize(structured), metadata);
    }

    RuntimeGraphSpecExecutionResult executeTemplate(GraphSpec.Node node, RuntimeNodeExecutionContext execution) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String template = firstText(text(config.get("template")), text(config.get("content")));
        if (!StringUtils.hasText(template)) {
            return failure("RUNTIME_GRAPH_TEMPLATE_REQUIRED", "TEMPLATE requires template",
                    node.getId(), "TEMPLATE");
        }
        // Missing variables render as empty string — the same contract used by ANSWER and LLM.
        String rendered = firstText(valueResolver.renderTemplate(template, execution.variables()), "");
        Map<String, Object> metadata = nodeMetadata(node, "TEMPLATE");
        metadata.put("structuredOutput", rendered);
        metadata.put("traceSummary", Map.of("length", rendered.length()));
        return success(node, "TEMPLATE", rendered, metadata);
    }

    RuntimeGraphSpecExecutionResult executeVariableAggregator(GraphSpec.Node node,
                                                              RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        Map<String, Object> config = aggregateConfig(node);
        String mode = firstText(text(config.get("aggregateMode")), text(config.get("mode")), "object")
                .toLowerCase(Locale.ROOT);
        Object rawItems = config.get("items");
        if (!(rawItems instanceof List<?> items) || items.isEmpty()) {
            return failure("RUNTIME_GRAPH_AGGREGATE_ITEMS_REQUIRED",
                    "VARIABLE_AGGREGATOR requires items", node.getId(), "VARIABLE_AGGREGATOR");
        }
        List<Map<String, Object>> normalizedItems = new ArrayList<>();
        for (Object raw : items) {
            Map<String, Object> item = mapValue(raw);
            if (item == null) {
                continue;
            }
            String name = firstText(text(item.get("name")), "value");
            String source = firstText(text(item.get("source")), "lastOutput");
            Object value = valueResolver.renderInputValue(source, context);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("source", source);
            row.put("value", value);
            normalizedItems.add(row);
        }
        Object structured;
        String answer;
        switch (mode) {
            case "array" -> {
                List<Object> values = normalizedItems.stream().map(item -> item.get("value")).toList();
                structured = values;
                answer = serialize(values);
            }
            case "text" -> {
                String template = firstText(text(config.get("template")), "");
                if (!StringUtils.hasText(template)) {
                    StringBuilder builder = new StringBuilder();
                    for (Map<String, Object> item : normalizedItems) {
                        if (!builder.isEmpty()) {
                            builder.append('\n');
                        }
                        builder.append(item.get("value") == null ? "" : item.get("value"));
                    }
                    answer = builder.toString();
                } else {
                    Map<String, Object> local = new LinkedHashMap<>(context);
                    for (Map<String, Object> item : normalizedItems) {
                        local.put(String.valueOf(item.get("name")), item.get("value"));
                    }
                    answer = firstText(valueResolver.renderTemplate(template, local), "");
                }
                structured = answer;
            }
            default -> {
                Map<String, Object> object = new LinkedHashMap<>();
                for (Map<String, Object> item : normalizedItems) {
                    object.put(String.valueOf(item.get("name")), item.get("value"));
                }
                structured = object;
                answer = serialize(object);
            }
        }
        Map<String, Object> metadata = nodeMetadata(node, "VARIABLE_AGGREGATOR");
        metadata.put("structuredOutput", structured);
        metadata.put("aggregateMode", mode);
        metadata.put("traceSummary", Map.of("mode", mode, "itemCount", normalizedItems.size()));
        return success(node, "VARIABLE_AGGREGATOR", answer, metadata);
    }

    /**
     * {@code params} is a reserved Runtime namespace. USER_INPUT is its designated writer, so this
     * deliberately bypasses the business output-alias writer while preserving flattened lookups.
     */
    private void writeUserInputParams(GraphSpec.Node node, Map<String, Object> context, String rawInput) {
        List<GraphSpecUserInputContract.InputField> fields = GraphSpecUserInputContract.inputFields(node);
        if (fields.isEmpty()) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> existing = mapValue(context.get(WorkflowVariableNamespaces.PARAMS_ROOT));
        if (existing != null) {
            params.putAll(existing);
        }
        for (GraphSpecUserInputContract.InputField field : fields) {
            if (!StringUtils.hasText(field.name())) {
                continue;
            }
            Object value = resolveUserInputFieldValue(field, context, rawInput);
            if (value == null && field.defaultValue() != null) {
                value = field.defaultValue();
            }
            params.put(field.name(), value == null ? "" : value);
        }
        context.put(WorkflowVariableNamespaces.PARAMS_ROOT, params);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            context.put(WorkflowVariableNamespaces.PARAMS_ROOT + "." + entry.getKey(), entry.getValue());
        }
    }

    private Object resolveUserInputFieldValue(GraphSpecUserInputContract.InputField field,
                                              Map<String, Object> context,
                                              String rawInput) {
        String source = field.source() == null ? "" : field.source().trim();
        if (!StringUtils.hasText(source)
                || "input".equals(source)
                || "input.message".equals(source)
                || "message".equals(source)
                || "userInput".equals(source)
                || "query".equals(source)) {
            return rawInput;
        }
        Object value = valueResolver.resolveContextValue(source, context);
        if (value != null) {
            return value;
        }
        if (("input." + field.name()).equals(source)) {
            Object namedValue = valueResolver.resolveContextValue(field.name(), context);
            if (namedValue == null) {
                namedValue = valueResolver.resolveContextValue("inputParams." + field.name(), context);
            }
            if (namedValue != null) {
                return namedValue;
            }
            return rawInput;
        }
        return null;
    }

    private Map<String, Object> aggregateConfig(GraphSpec.Node node) {
        return mergedConfig(node, "aggregateConfig");
    }

    private Map<String, Object> conditionConfig(GraphSpec.Node node) {
        return mergedConfig(node, "conditionConfig");
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
            return valueResolver.renderTemplate(candidate, context);
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
        Object resolved = valueResolver.resolveContextValue(candidate, context);
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
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // Fall through to string comparison.
            }
        }
        return null;
    }

    private RuntimeGraphSpecExecutionResult success(GraphSpec.Node node,
                                                    String nodeType,
                                                    String answer,
                                                    Map<String, Object> metadata) {
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                answer,
                node.getId(),
                nodeType,
                List.of(step("execute-node", node.getId())),
                metadata);
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

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private Object firstPresent(Object first, Object fallback) {
        return first != null ? first : fallback;
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
