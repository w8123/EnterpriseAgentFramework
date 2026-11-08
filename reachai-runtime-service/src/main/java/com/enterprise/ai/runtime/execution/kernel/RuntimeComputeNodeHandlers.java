package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure computation nodes: CODE (sandboxed JavaScript or safe expressions) and LIST_OPERATOR.
 * Neither touches business systems, the network or files.
 */
final class RuntimeComputeNodeHandlers {

    static final int LIST_MAX_ITEMS = 10_000;
    static final Set<String> LIST_OPERATIONS = Set.of("filter", "sort", "unique", "limit", "offset", "pluck");

    private final ObjectMapper objectMapper;
    private final RuntimeNodeValueResolver valueResolver;
    private final RuntimeDeterministicNodeHandlers conditions;

    RuntimeComputeNodeHandlers(ObjectMapper objectMapper,
                               RuntimeNodeValueResolver valueResolver,
                               RuntimeDeterministicNodeHandlers conditions) {
        this.objectMapper = objectMapper;
        this.valueResolver = valueResolver;
        this.conditions = conditions;
    }

    RuntimeGraphSpecExecutionResult executeCode(GraphSpec.Node node, RuntimeNodeExecutionContext execution) {
        Map<String, Object> config = merged(node, "codeConfig");
        Map<String, Object> context = execution.variables();
        String language = text(config.get("language"));
        if (!"javascript".equalsIgnoreCase(language)) {
            return executeExpressionCode(node, config, context);
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        Object rawInputs = config.get("inputs");
        if (rawInputs instanceof Map<?, ?> map) {
            map.forEach((key, value) -> inputs.put(String.valueOf(key), valueResolver.renderInputValue(value, context)));
        }
        long timeoutMs = longValue(config.get("timeoutMs"), RuntimeCodeSandbox.DEFAULT_TIMEOUT_MS);
        Object output;
        try {
            String inputJson = objectMapper.writeValueAsString(inputs);
            String resultJson = RuntimeCodeSandbox.run(text(config.get("code")), inputJson, timeoutMs);
            output = objectMapper.readValue(resultJson, Object.class);
        } catch (RuntimeCodeSandbox.CodeFailure ex) {
            return failure(ex.code(), ex.getMessage(), node.getId(), "CODE");
        } catch (Exception ex) {
            return failure("RUNTIME_GRAPH_CODE_FAILED", "代码节点输入或输出无法转成 JSON", node.getId(), "CODE");
        }
        if (!(output instanceof Map<?, ?>)) {
            return failure("RUNTIME_GRAPH_CODE_OUTPUT_INVALID",
                    "main(inputs) 需要返回一个对象，例如 return { result: 1 }", node.getId(), "CODE");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) output;
        List<String> missing = new ArrayList<>();
        if (config.get("outputs") instanceof Map<?, ?> declared) {
            for (Object key : declared.keySet()) {
                if (!structured.containsKey(String.valueOf(key))) missing.add(String.valueOf(key));
            }
        }
        if (!missing.isEmpty()) {
            return failure("RUNTIME_GRAPH_CODE_OUTPUT_MISSING",
                    "代码返回的对象缺少声明的输出字段：" + String.join("、", missing), node.getId(), "CODE");
        }
        Map<String, Object> metadata = metadata(node, "CODE");
        metadata.put("structuredOutput", structured);
        metadata.put("traceSummary", Map.of("language", "javascript", "inputCount", inputs.size(),
                "outputKeys", List.copyOf(structured.keySet())));
        return success(node, "CODE", serialize(structured), metadata);
    }

    private RuntimeGraphSpecExecutionResult executeExpressionCode(GraphSpec.Node node,
                                                                  Map<String, Object> config,
                                                                  Map<String, Object> context) {
        Map<String, Object> structured = new LinkedHashMap<>();
        if (config.get("outputs") instanceof Map<?, ?> outputs) {
            outputs.forEach((key, value) -> structured.put(String.valueOf(key), valueResolver.renderInputValue(value, context)));
        }
        if (structured.isEmpty()) {
            return failure("RUNTIME_GRAPH_CODE_OUTPUTS_REQUIRED", "表达式模式至少需要一个输出字段", node.getId(), "CODE");
        }
        Map<String, Object> metadata = metadata(node, "CODE");
        metadata.put("structuredOutput", structured);
        metadata.put("traceSummary", Map.of("language", "expression", "outputKeys", List.copyOf(structured.keySet())));
        return success(node, "CODE", serialize(structured), metadata);
    }

    RuntimeGraphSpecExecutionResult executeListOperator(GraphSpec.Node node, RuntimeNodeExecutionContext execution) {
        Map<String, Object> config = merged(node, "listConfig");
        Map<String, Object> context = execution.variables();
        String source = text(config.get("source"));
        if (!StringUtils.hasText(source)) {
            return failure("RUNTIME_GRAPH_LIST_SOURCE_REQUIRED", "列表处理需要选择一个列表", node.getId(), "LIST_OPERATOR");
        }
        Object raw = valueResolver.renderInputValue(source, context);
        if (raw instanceof String text && text.trim().startsWith("[")) {
            try {
                raw = objectMapper.readValue(text, new TypeReference<List<Object>>() { });
            } catch (Exception ignored) {
                // Not JSON; fall through to the type check below.
            }
        }
        if (raw == null) raw = List.of();
        if (!(raw instanceof Collection<?> collection)) {
            return failure("RUNTIME_GRAPH_LIST_SOURCE_NOT_LIST", "「" + source + "」不是列表", node.getId(), "LIST_OPERATOR");
        }
        if (collection.size() > LIST_MAX_ITEMS) {
            return failure("RUNTIME_GRAPH_LIST_TOO_LARGE", "列表超过 " + LIST_MAX_ITEMS + " 项", node.getId(), "LIST_OPERATOR");
        }
        List<Object> items = new ArrayList<>(collection);
        int inputCount = items.size();
        List<String> applied = new ArrayList<>();
        Object rawOperations = config.get("operations");
        if (rawOperations instanceof List<?> operations) {
            for (Object rawOperation : operations) {
                if (!(rawOperation instanceof Map<?, ?> operation)) continue;
                String type = lower(text(operation.get("type")));
                if (!LIST_OPERATIONS.contains(type)) {
                    return failure("RUNTIME_GRAPH_LIST_OPERATION_INVALID", "不支持的列表操作：" + type,
                            node.getId(), "LIST_OPERATOR");
                }
                items = apply(type, operation, items, context);
                applied.add(type);
            }
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("items", items);
        structured.put("count", items.size());
        structured.put("first", items.isEmpty() ? null : items.get(0));
        structured.put("last", items.isEmpty() ? null : items.get(items.size() - 1));
        Map<String, Object> metadata = metadata(node, "LIST_OPERATOR");
        metadata.put("structuredOutput", structured);
        metadata.put("traceSummary", Map.of("inputCount", inputCount, "outputCount", items.size(), "operations", applied));
        return success(node, "LIST_OPERATOR", serialize(structured), metadata);
    }

    private List<Object> apply(String type, Map<?, ?> operation, List<Object> items, Map<String, Object> context) {
        String field = text(operation.get("field"));
        switch (type) {
            case "filter" -> {
                Object rawConditions = operation.get("conditions");
                List<?> list = rawConditions instanceof List<?> l ? l : List.of();
                if (list.isEmpty()) return items;
                String logic = firstText(text(operation.get("logic")), "AND");
                Map<String, Object> local = new HashMap<>(context);
                List<Object> kept = new ArrayList<>();
                for (Object item : items) {
                    local.put("item", item);
                    if (conditions.evaluateConditionGroup(list, logic, local)) kept.add(item);
                }
                return kept;
            }
            case "sort" -> {
                boolean desc = "desc".equalsIgnoreCase(text(operation.get("order")));
                Comparator<Object> comparator = (left, right) -> compare(field(left, field), field(right, field));
                List<Object> sorted = new ArrayList<>(items);
                sorted.sort(desc ? comparator.reversed() : comparator);
                return sorted;
            }
            case "unique" -> {
                Set<String> seen = new LinkedHashSet<>();
                List<Object> unique = new ArrayList<>();
                for (Object item : items) {
                    if (seen.add(serialize(field(item, field)))) unique.add(item);
                }
                return unique;
            }
            case "limit" -> {
                int count = (int) Math.max(0, longValue(operation.get("count"), items.size()));
                return new ArrayList<>(items.subList(0, Math.min(count, items.size())));
            }
            case "offset" -> {
                int count = (int) Math.max(0, longValue(operation.get("count"), 0));
                return count >= items.size() ? new ArrayList<>() : new ArrayList<>(items.subList(count, items.size()));
            }
            case "pluck" -> {
                if (!StringUtils.hasText(field)) return items;
                List<Object> values = new ArrayList<>();
                for (Object item : items) values.add(field(item, field));
                return values;
            }
            default -> {
                return items;
            }
        }
    }

    static Object field(Object item, String path) {
        if (!StringUtils.hasText(path)) return item;
        Object current = item;
        for (String part : path.trim().split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    private static int compare(Object left, Object right) {
        if (left == null || right == null) return left == right ? 0 : left == null ? 1 : -1;
        Double l = number(left);
        Double r = number(right);
        if (l != null && r != null) return Double.compare(l, r);
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<String, Object> merged(GraphSpec.Node node, String nestedKey) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> merged = new LinkedHashMap<>();
        if (config.get(nestedKey) instanceof Map<?, ?> nested) {
            nested.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }
        config.forEach((key, value) -> {
            if (!nestedKey.equals(key) && value != null) merged.put(key, value);
        });
        return merged;
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private RuntimeGraphSpecExecutionResult success(GraphSpec.Node node, String type, String answer,
                                                    Map<String, Object> metadata) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", "execute-node");
        step.put("detail", node.getId());
        return new RuntimeGraphSpecExecutionResult(true, "RUNTIME_GRAPH_EXECUTED", answer, node.getId(), type,
                List.of(step), metadata);
    }

    private RuntimeGraphSpecExecutionResult failure(String code, String answer, String nodeId, String type) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", nodeId);
        metadata.put("nodeType", type);
        return new RuntimeGraphSpecExecutionResult(false, code, answer, nodeId, type, List.of(), metadata);
    }

    private static Map<String, Object> metadata(GraphSpec.Node node, String type) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", node.getId());
        metadata.put("nodeType", type);
        return metadata;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }
}
