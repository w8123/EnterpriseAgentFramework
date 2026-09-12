package com.enterprise.ai.runtime.execution.interaction;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GraphSpec INTERACTION node pause/resume semantics.
 * Resume payload must be node-scoped via {@link WorkflowInteractionCodes#RESUME_CONTEXT_KEY}.
 */
public final class WorkflowInteractionNodeHandler {

    private WorkflowInteractionNodeHandler() {
    }

    public static RuntimeGraphSpecExecutionResult execute(GraphSpec.Node node, Map<String, Object> context) {
        Map<String, Object> config = WorkflowInteractionUiRequestFactory.configOf(node);
        WorkflowInteractionType type = WorkflowInteractionType.from(config.get("interactionType"));

        if (type == WorkflowInteractionType.CUSTOM && !isSupportedCustom(config)) {
            return result(false, WorkflowInteractionCodes.UNSUPPORTED_CUSTOM,
                    "CUSTOM interaction requires supported rendererKey and schema",
                    node.getId(), Map.of("interactionType", type.name()));
        }

        if (type == WorkflowInteractionType.PRESENT_OUTPUT) {
            return presentOutput(node, context, config, type);
        }

        Map<String, Object> resume = matchingResume(context, node.getId());
        if (resume == null) {
            // Legacy global submittedPayload is intentionally ignored to prevent cross-node leakage.
            return waiting(node, context, config, type, null);
        }

        String expectedId = text(context.get(WorkflowInteractionCodes.PENDING_INTERACTION_ID_KEY));
        String resumeId = text(resume.get("interactionId"));
        if (StringUtils.hasText(expectedId) && StringUtils.hasText(resumeId) && !expectedId.equals(resumeId)) {
            return waiting(node, context, config, type,
                    List.of("interactionId does not match waiting interaction"));
        }
        if (StringUtils.hasText(resumeId) && StringUtils.hasText(expectedId) == false) {
            // first resume after restore: accept matching nodeId
        }

        String action = firstText(text(resume.get("action")), "submit");
        if ("cancel".equalsIgnoreCase(action)) {
            clearResume(context);
            Map<String, Object> metadata = baseMetadata(node, type);
            metadata.put("route", "cancel");
            metadata.put("lastRoute", "cancel");
            metadata.put("cancelled", true);
            return result(false, "RUNTIME_GRAPH_CANCELLED",
                    "Interaction cancelled at node: " + node.getId(), node.getId(), metadata);
        }

        ValidationOutcome validation = validate(type, config, action, resume);
        if (!validation.ok()) {
            // Keep waiting; do not clear pending interaction id.
            clearResume(context);
            return waiting(node, context, config, type, validation.errors());
        }

        Map<String, Object> values = validation.values();
        String route = validation.route();
        applyOutputs(node, context, config, type, action, values, route);
        clearResume(context);
        clearPending(context);

        Map<String, Object> metadata = baseMetadata(node, type);
        metadata.put("submittedPayload", values);
        metadata.put("structuredOutput", values);
        metadata.put("route", route);
        metadata.put("lastRoute", route);
        metadata.put("action", action);
        return result(true, "RUNTIME_GRAPH_EXECUTED", String.valueOf(values), node.getId(), metadata);
    }

    private static RuntimeGraphSpecExecutionResult presentOutput(GraphSpec.Node node,
                                                                 Map<String, Object> context,
                                                                 Map<String, Object> config,
                                                                 WorkflowInteractionType type) {
        String interactionId = WorkflowInteractionUiRequestFactory.newInteractionId();
        WorkflowInteractionUiRequest uiRequest =
                WorkflowInteractionUiRequestFactory.build(node, context, interactionId, type);
        Object data = uiRequest.data();
        String outputAlias = firstText(text(config.get("outputAlias")), "interaction_output");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("acknowledged", true);
        output.put("data", data);
        context.put(outputAlias, output);
        context.put("lastOutput", data == null ? output : data);
        Map<String, Object> metadata = baseMetadata(node, type);
        metadata.put("uiRequest", uiRequest.toMap());
        metadata.put("interactionId", interactionId);
        metadata.put("displayOnly", true);
        metadata.put("structuredOutput", output);
        metadata.put("route", "continue");
        metadata.put("lastRoute", "continue");
        return result(true, "RUNTIME_GRAPH_EXECUTED",
                data == null ? "presented" : String.valueOf(data), node.getId(), metadata);
    }

    private static RuntimeGraphSpecExecutionResult waiting(GraphSpec.Node node,
                                                           Map<String, Object> context,
                                                           Map<String, Object> config,
                                                           WorkflowInteractionType type,
                                                           List<String> errors) {
        String interactionId = reuseOrCreateInteractionId(context, node.getId());
        context.put(WorkflowInteractionCodes.PENDING_INTERACTION_ID_KEY, interactionId);
        context.put(WorkflowInteractionCodes.PENDING_INTERACTION_NODE_KEY, node.getId());
        // Prevent stale global payload from auto-consuming future interactions.
        context.remove("submittedPayload");
        if (context.get("values") instanceof Map<?, ?> values
                && values.containsKey("action")
                && values.containsKey("values")) {
            context.remove("values");
        }

        WorkflowInteractionUiRequest uiRequest =
                WorkflowInteractionUiRequestFactory.build(node, context, interactionId, type);
        Map<String, Object> metadata = baseMetadata(node, type);
        metadata.put("uiRequest", uiRequest.toMap());
        metadata.put("interactionId", interactionId);
        metadata.put("status", "WAITING_USER");
        if (errors != null && !errors.isEmpty()) {
            metadata.put("validationErrors", errors);
            metadata.put("missing", errors);
        }
        String message = errors == null || errors.isEmpty()
                ? "Interaction node is waiting for user input: " + node.getId()
                : "Interaction validation failed: " + String.join("; ", errors);
        return result(false, WorkflowInteractionCodes.WAITING, message, node.getId(), metadata);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> matchingResume(Map<String, Object> context, String nodeId) {
        Object raw = context.get(WorkflowInteractionCodes.RESUME_CONTEXT_KEY);
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> resume = new LinkedHashMap<>((Map<String, Object>) map);
        String resumeNodeId = text(resume.get("nodeId"));
        if (StringUtils.hasText(resumeNodeId) && !nodeId.equals(resumeNodeId)) {
            return null;
        }
        return resume;
    }

    private static void clearResume(Map<String, Object> context) {
        context.remove(WorkflowInteractionCodes.RESUME_CONTEXT_KEY);
        context.remove("submittedPayload");
    }

    private static void clearPending(Map<String, Object> context) {
        context.remove(WorkflowInteractionCodes.PENDING_INTERACTION_ID_KEY);
        context.remove(WorkflowInteractionCodes.PENDING_INTERACTION_NODE_KEY);
    }

    private static String reuseOrCreateInteractionId(Map<String, Object> context, String nodeId) {
        String pendingNode = text(context.get(WorkflowInteractionCodes.PENDING_INTERACTION_NODE_KEY));
        String pendingId = text(context.get(WorkflowInteractionCodes.PENDING_INTERACTION_ID_KEY));
        if (nodeId.equals(pendingNode) && StringUtils.hasText(pendingId)) {
            return pendingId;
        }
        return WorkflowInteractionUiRequestFactory.newInteractionId();
    }

    private static ValidationOutcome validate(WorkflowInteractionType type,
                                              Map<String, Object> config,
                                              String action,
                                              Map<String, Object> resume) {
        Map<String, Object> values = extractValues(resume);
        return switch (type) {
            case COLLECT_INPUT, REVIEW_EDIT -> validateCollect(config, values);
            case USER_CHOICE -> validateChoice(config, values, action);
            case CONFIRM_ACTION -> validateConfirm(action, values);
            case CUSTOM -> validateCollect(config, values);
            case PRESENT_OUTPUT -> ValidationOutcome.ok(values, "continue");
        };
    }

    private static ValidationOutcome validateCollect(Map<String, Object> config, Map<String, Object> values) {
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> fields = fieldsOf(config);
        for (Map<String, Object> field : fields) {
            String key = firstText(text(field.get("key")), text(field.get("name")));
            if (!StringUtils.hasText(key)) {
                continue;
            }
            boolean required = Boolean.TRUE.equals(field.get("required"));
            Object value = values.get(key);
            if (required && isBlank(value)) {
                errors.add("required field missing: " + key);
                continue;
            }
            if (value == null) {
                continue;
            }
            String fieldType = firstText(text(field.get("type")), "string").toLowerCase(Locale.ROOT);
            if ("number".equals(fieldType) || "integer".equals(fieldType) || "int".equals(fieldType)) {
                if (!(value instanceof Number) && !isNumeric(value)) {
                    errors.add("field type mismatch: " + key + " expects number");
                }
            } else if ("boolean".equals(fieldType) || "bool".equals(fieldType)) {
                if (!(value instanceof Boolean)
                        && !"true".equalsIgnoreCase(String.valueOf(value))
                        && !"false".equalsIgnoreCase(String.valueOf(value))) {
                    errors.add("field type mismatch: " + key + " expects boolean");
                }
            }
            List<Object> allowed = allowedValues(field.get("options"));
            if (!selectionAllowed(allowed, value, "multi_select".equals(fieldType))) {
                errors.add("field value not allowed: " + key);
            }
        }
        if (!errors.isEmpty()) {
            return ValidationOutcome.fail(errors);
        }
        return ValidationOutcome.ok(values, "submit");
    }

    private static ValidationOutcome validateChoice(Map<String, Object> config,
                                                    Map<String, Object> values,
                                                    String action) {
        List<Object> allowed = allowedValues(config.get("options"));
        if (allowed.isEmpty()) {
            for (Map<String, Object> field : fieldsOf(config)) {
                allowed.addAll(allowedValues(field.get("options")));
            }
        }
        Object selected = firstPresent(values.get("selected"),
                firstPresent(values.get("value"), values.get("choice")));
        if (selected == null && values.size() == 1) {
            selected = values.values().iterator().next();
        }
        if (selected == null && StringUtils.hasText(action)
                && !"submit".equalsIgnoreCase(action)
                && !"cancel".equalsIgnoreCase(action)) {
            selected = action;
        }
        if (selected == null) {
            return ValidationOutcome.fail(List.of("choice value is required"));
        }
        boolean multiple = "multi_select".equals(
                WorkflowInteractionUiRequestFactory.resolveComponent(WorkflowInteractionType.USER_CHOICE, config));
        if (!selectionAllowed(allowed, selected, multiple)) {
            return ValidationOutcome.fail(List.of("choice value is not in declared options"));
        }
        if (multiple && isBlank(selected) && fieldsOf(config).stream().anyMatch(field -> Boolean.TRUE.equals(field.get("required")))) {
            return ValidationOutcome.fail(List.of("choice value is required"));
        }
        Map<String, Object> normalized = new LinkedHashMap<>(values);
        normalized.put("selected", selected);
        normalized.put("value", selected);
        String route = String.valueOf(selected);
        return ValidationOutcome.ok(normalized, route);
    }

    private static ValidationOutcome validateConfirm(String action, Map<String, Object> values) {
        String normalized = firstText(action, "confirm").toLowerCase(Locale.ROOT);
        Set<String> allowed = Set.of("confirm", "reject", "cancel", "approve", "deny");
        if (!allowed.contains(normalized)) {
            return ValidationOutcome.fail(List.of("confirm action must be confirm/reject/cancel"));
        }
        String route = switch (normalized) {
            case "approve" -> "confirm";
            case "deny" -> "reject";
            default -> normalized;
        };
        Map<String, Object> normalizedValues = new LinkedHashMap<>(values);
        normalizedValues.put("action", route);
        normalizedValues.put("confirmed", "confirm".equals(route));
        return ValidationOutcome.ok(normalizedValues, route);
    }

    @SuppressWarnings("unchecked")
    private static void applyOutputs(GraphSpec.Node node,
                                     Map<String, Object> context,
                                     Map<String, Object> config,
                                     WorkflowInteractionType type,
                                     String action,
                                     Map<String, Object> values,
                                     String route) {
        String outputAlias = firstText(text(config.get("outputAlias")), "interaction_output");
        Map<String, Object> output = new LinkedHashMap<>(values);
        output.put("action", action);
        output.put("route", route);
        context.put(outputAlias, output);
        context.put("lastOutput", output);
        context.put("route", route);
        context.put("lastRoute", route);

        for (Map<String, Object> field : fieldsOf(config)) {
            String key = firstText(text(field.get("key")), text(field.get("name")));
            if (!StringUtils.hasText(key) || !values.containsKey(key)) {
                continue;
            }
            Object value = values.get(key);
            String targetPath = text(field.get("targetPath"));
            if (StringUtils.hasText(targetPath)) {
                context.put(targetPath, value);
            }
            context.put(key, value);
        }

        if (type == WorkflowInteractionType.CONFIRM_ACTION && "reject".equalsIgnoreCase(route)) {
            context.put("interactionRejected", true);
        }

        Map<String, Object> nodeOutputs = context.get("nodeOutput") instanceof Map<?, ?> existing
                ? new LinkedHashMap<>((Map<String, Object>) existing)
                : new LinkedHashMap<>();
        nodeOutputs.put(node.getId(), output);
        context.put("nodeOutput", nodeOutputs);
    }

    private static Map<String, Object> extractValues(Map<String, Object> resume) {
        Object raw = firstPresent(resume.get("values"),
                firstPresent(resume.get("submittedPayload"), resume.get("payload")));
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    values.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return values;
        }
        Map<String, Object> fallback = new LinkedHashMap<>(resume);
        fallback.remove("interactionId");
        fallback.remove("nodeId");
        fallback.remove("action");
        fallback.remove("idempotencyKey");
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fieldsOf(Map<String, Object> config) {
        Object raw = config.get("fields");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                fields.add((Map<String, Object>) map);
            }
        }
        return fields;
    }

    private static List<Object> allowedValues(Object optionsRaw) {
        List<Object> allowed = new ArrayList<>();
        if (!(optionsRaw instanceof List<?> list)) {
            return allowed;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object value = firstPresent(map.get("value"), map.get("id"));
                if (value != null) {
                    allowed.add(value);
                }
            } else if (item != null) {
                allowed.add(item);
            }
        }
        return allowed;
    }

    private static boolean selectionAllowed(List<Object> allowed, Object value, boolean multiple) {
        if (multiple) {
            if (!(value instanceof List<?> values)) return false;
            return values.stream().allMatch(item -> item != null && !(item instanceof Map<?, ?>) && !(item instanceof List<?>)
                    && (allowed.isEmpty() || allowedContains(allowed, item)));
        }
        return allowed.isEmpty() || allowedContains(allowed, value);
    }

    private static boolean allowedContains(List<Object> allowed, Object value) {
        for (Object item : allowed) {
            if (item == null) {
                continue;
            }
            if (item.equals(value) || String.valueOf(item).equals(String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSupportedCustom(Map<String, Object> config) {
        Map<String, Object> renderSchema = config.get("renderSchema") instanceof Map<?, ?> map
                ? castMap(map) : Map.of();
        String rendererKey = text(renderSchema.get("rendererKey"));
        if (!StringUtils.hasText(rendererKey)) {
            rendererKey = text(config.get("rendererKey"));
        }
        return WorkflowInteractionCustomRenderers.isSupported(rendererKey);
    }

    private static Map<String, Object> baseMetadata(GraphSpec.Node node, WorkflowInteractionType type) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", node.getId());
        metadata.put("nodeType", "INTERACTION");
        metadata.put("interactionType", type.name());
        return metadata;
    }

    private static RuntimeGraphSpecExecutionResult result(boolean success,
                                                          String code,
                                                          String answer,
                                                          String nodeId,
                                                          Map<String, Object> metadata) {
        return new RuntimeGraphSpecExecutionResult(
                success,
                code,
                answer,
                nodeId,
                "INTERACTION",
                List.of(Map.of("step", "execute-node", "detail", nodeId == null ? "" : nodeId)),
                metadata == null ? Map.of() : metadata);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return !StringUtils.hasText(text);
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }

    private static boolean isNumeric(Object value) {
        try {
            Double.parseDouble(String.valueOf(value).trim());
            return true;
        } catch (Exception ex) {
            return false;
        }
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

    private record ValidationOutcome(boolean ok, Map<String, Object> values, String route, List<String> errors) {
        static ValidationOutcome ok(Map<String, Object> values, String route) {
            return new ValidationOutcome(true, values == null ? Map.of() : values, route, List.of());
        }

        static ValidationOutcome fail(List<String> errors) {
            return new ValidationOutcome(false, Map.of(), null, errors == null ? List.of() : errors);
        }
    }
}
