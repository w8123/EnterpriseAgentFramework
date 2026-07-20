package com.enterprise.ai.runtime.execution.interaction;

import com.enterprise.ai.agent.graph.GraphSpec;
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

        List<Map<String, Object>> fields = normalizeFields(config.get("fields"));
        List<Map<String, Object>> options = normalizeOptions(config.get("options"), fields);
        List<Map<String, Object>> actions = resolveActions(type, config);

        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("interactionType", type.name());
        extension.put("outputAlias", firstText(text(config.get("outputAlias")), "interaction_output"));
        Object renderSchema = config.get("renderSchema");
        if (renderSchema instanceof Map<?, ?> schemaMap && !schemaMap.isEmpty()) {
            extension.put("renderSchema", schemaMap);
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
                mapValue(config.get("schema")),
                actions,
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
        if ("lastOutput".equals(expression) || "previousOutput".equals(expression)) {
            return firstPresent(context.get("lastOutput"), context.get("previousOutput"));
        }
        if (context.containsKey(expression)) {
            return context.get(expression);
        }
        return context.get("lastOutput");
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
