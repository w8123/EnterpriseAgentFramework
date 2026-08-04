package com.enterprise.ai.runtime.execution.interaction;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.context.WorkflowVariableNamespaces;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class WorkflowInteractionUiRequestFactory {

    private static final java.util.Set<String> BLOCKED_PATH_SEGMENTS = java.util.Set.of(
            "__proto__", "prototype", "constructor");

    private WorkflowInteractionUiRequestFactory() {
    }

    public static String newInteractionId() {
        return WorkflowInteractionCodes.ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    public static WorkflowInteractionUiRequest build(GraphSpec.Node node,
                                                     Map<String, Object> context,
                                                     String interactionId,
                                                     WorkflowInteractionType type) {
        Map<String, Object> config = configOf(node);
        String component = resolveComponent(type, config);
        Integer ttlSeconds = intValue(firstPresent(config.get("ttlSeconds"),
                mapValue(config.get("behavior")).get("ttlSeconds")), 3600);
        String expiresAt = Instant.now().plus(ttlSeconds, ChronoUnit.SECONDS).toString();
        Map<String, Object> behavior = new LinkedHashMap<>(mapValue(config.get("behavior")));
        behavior.put("blocking", type.blocking());
        behavior.putIfAbsent("askMissing", true);
        Map<String, Object> presentation = WorkflowInteractionPresentationPolicy.resolve(
                type, config.get("presentation"));

        List<Map<String, Object>> fields = normalizeFields(config.get("fields"));
        List<Map<String, Object>> options = normalizeOptions(config.get("options"), fields);
        List<Map<String, Object>> actions = resolveActions(type, config);

        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("interactionType", type.name());
        extension.put("outputAlias", firstText(text(config.get("outputAlias")), "interaction_output"));
        Map<String, Object> renderSchema = mapValue(config.get("renderSchema"));
        if (!renderSchema.isEmpty()) {
            extension.put("renderSchema", renderSchema);
        }
        String rendererKey = firstText(text(renderSchema.get("rendererKey")), text(config.get("rendererKey")));
        if (StringUtils.hasText(rendererKey)) {
            extension.put("rendererKey", rendererKey);
        }

        Object data = resolvePresentData(type, config, context);
        Map<String, Object> summary = type == WorkflowInteractionType.PRESENT_OUTPUT
                ? Map.of("mode", "display")
                : Map.of();

        return new WorkflowInteractionUiRequest(
                WorkflowInteractionCodes.PROTOCOL_VERSION,
                interactionId,
                type.name(),
                component,
                text(context.get("workflowId")),
                firstText(text(context.get("workflowVersionId")), text(context.get("workflowVersion"))),
                node.getId(),
                text(context.get("runId")),
                text(context.get("traceId")),
                firstText(text(config.get("title")), text(node.getName()), node.getId()),
                text(config.get("message")),
                ttlSeconds,
                expiresAt,
                fields.isEmpty() ? null : fields,
                options.isEmpty() ? null : options,
                mapValue(config.get("prefilled")),
                data,
                summary.isEmpty() ? null : summary,
                resolveUiSchema(config, renderSchema),
                actions,
                presentation,
                behavior,
                extension);
    }

    public static String resolveComponent(WorkflowInteractionType type, Map<String, Object> config) {
        String configured = normalizeComponent(text(config.get("component")));
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        return switch (type) {
            case COLLECT_INPUT, REVIEW_EDIT -> "form";
            case USER_CHOICE -> "choice";
            case CONFIRM_ACTION -> "confirm";
            case PRESENT_OUTPUT -> "detail";
            case CUSTOM -> "custom";
        };
    }

    static Map<String, Object> configOf(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(node.getConfig());
        Object nested = config.get("interactionConfig");
        if (nested instanceof Map<?, ?> nestedMap) {
            for (Map.Entry<?, ?> entry : nestedMap.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey());
                config.putIfAbsent(key, entry.getValue());
            }
        }
        return config;
    }

    private static Object resolvePresentData(WorkflowInteractionType type,
                                             Map<String, Object> config,
                                             Map<String, Object> context) {
        if (type != WorkflowInteractionType.PRESENT_OUTPUT) {
            return config.get("data");
        }
        Object explicit = firstPresent(config.get("data"), config.get("summary"));
        if (explicit != null) {
            return explicit;
        }
        String expression = firstText(text(config.get("dataExpression")), "lastOutput");
        ResolvedValue resolved = resolveContextExpression(expression, context);
        if (resolved.found()) {
            return resolved.value();
        }
        // Compatibility for drafts created before dotted-path resolution: an unresolved alias
        // historically displayed lastOutput because PRESENT_OUTPUT immediately followed its producer.
        return firstPresent(context.get("lastOutput"), context.get("previousOutput"));
    }

    private static Map<String, Object> resolveUiSchema(Map<String, Object> config,
                                                        Map<String, Object> renderSchema) {
        Map<String, Object> declared = mapValue(config.get("schema"));
        if (!declared.isEmpty()) {
            return declared;
        }
        Map<String, Object> nested = mapValue(renderSchema.get("schema"));
        if (!nested.isEmpty()) {
            return nested;
        }
        return new LinkedHashMap<>(renderSchema);
    }

    private static ResolvedValue resolveContextExpression(String rawExpression,
                                                           Map<String, Object> context) {
        String expression = normalizeExpression(rawExpression);
        if (!StringUtils.hasText(expression)) {
            return ResolvedValue.missing();
        }
        ResolvedValue direct = mapEntry(context, expression);
        if (direct.found()) {
            return direct;
        }
        ResolvedValue nested = readPath(context, expression);
        if (nested.found()) {
            return nested;
        }

        String root = expression.contains(".")
                ? expression.substring(0, expression.indexOf('.'))
                : expression;
        if (!WorkflowVariableNamespaces.isReservedRoot(root)) {
            String businessPath = WorkflowVariableNamespaces.VAR_ROOT + "." + expression;
            ResolvedValue businessDirect = mapEntry(context, businessPath);
            if (businessDirect.found()) {
                return businessDirect;
            }
            return readPath(context, businessPath);
        }
        return ResolvedValue.missing();
    }

    private static String normalizeExpression(String rawExpression) {
        if (!StringUtils.hasText(rawExpression)) {
            return null;
        }
        String expression = rawExpression.trim();
        if (expression.startsWith("{{") && expression.endsWith("}}") && expression.length() > 4) {
            expression = expression.substring(2, expression.length() - 2).trim();
        }
        if (expression.startsWith("$.")) {
            expression = expression.substring(2);
        } else if (expression.startsWith("$")) {
            expression = expression.substring(1);
        }
        while (expression.startsWith(".")) {
            expression = expression.substring(1);
        }
        return expression;
    }

    private static ResolvedValue mapEntry(Map<String, Object> map, String key) {
        return map.containsKey(key)
                ? new ResolvedValue(true, map.get(key))
                : ResolvedValue.missing();
    }

    private static ResolvedValue readPath(Object source, String path) {
        if (!StringUtils.hasText(path)) {
            return ResolvedValue.missing();
        }
        Object current = source;
        for (String segment : path.split("\\.")) {
            if (!StringUtils.hasText(segment) || BLOCKED_PATH_SEGMENTS.contains(segment)) {
                return ResolvedValue.missing();
            }
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(segment)) {
                    return ResolvedValue.missing();
                }
                current = map.get(segment);
                continue;
            }
            if (current instanceof List<?> list) {
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException ex) {
                    return ResolvedValue.missing();
                }
                if (index < 0 || index >= list.size()) {
                    return ResolvedValue.missing();
                }
                current = list.get(index);
                continue;
            }
            return ResolvedValue.missing();
        }
        return new ResolvedValue(true, current);
    }

    private record ResolvedValue(boolean found, Object value) {
        private static ResolvedValue missing() {
            return new ResolvedValue(false, null);
        }
    }

    private static List<Map<String, Object>> resolveActions(WorkflowInteractionType type,
                                                            Map<String, Object> config) {
        List<Map<String, Object>> configured = normalizeActions(config.get("actions"));
        if (!configured.isEmpty()) {
            return configured;
        }
        return switch (type) {
            case CONFIRM_ACTION -> List.of(
                    action("confirm", "确认", "primary"),
                    action("reject", "拒绝", "danger"),
                    action("cancel", "取消", "default"));
            case USER_CHOICE -> List.of(action("submit", "提交", "primary"), action("cancel", "取消", "default"));
            case PRESENT_OUTPUT -> List.of();
            default -> List.of(action("submit", "提交", "primary"), action("cancel", "取消", "default"));
        };
    }

    private static Map<String, Object> action(String id, String label, String style) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("id", id);
        action.put("action", id);
        action.put("label", label);
        action.put("style", style);
        return action;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> normalizeFields(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> field = new LinkedHashMap<>((Map<String, Object>) map);
            String key = firstText(text(field.get("key")), text(field.get("name")), text(field.get("id")));
            if (!StringUtils.hasText(key)) {
                continue;
            }
            field.put("key", key);
            field.putIfAbsent("name", key);
            field.putIfAbsent("label", firstText(text(field.get("label")), key));
            field.putIfAbsent("type", "string");
            fields.add(field);
        }
        return fields;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> normalizeOptions(Object raw, List<Map<String, Object>> fields) {
        List<Map<String, Object>> options = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    options.add(new LinkedHashMap<>((Map<String, Object>) map));
                } else if (item != null) {
                    Map<String, Object> option = new LinkedHashMap<>();
                    option.put("value", item);
                    option.put("label", String.valueOf(item));
                    options.add(option);
                }
            }
        }
        if (!options.isEmpty()) {
            return options;
        }
        for (Map<String, Object> field : fields) {
            Object fieldOptions = field.get("options");
            if (fieldOptions instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        options.add(new LinkedHashMap<>((Map<String, Object>) map));
                    }
                }
            }
        }
        return options;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> normalizeActions(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                actions.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return actions;
    }

    private static String normalizeComponent(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private static Object firstPresent(Object first, Object second) {
        return first != null ? first : second;
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
