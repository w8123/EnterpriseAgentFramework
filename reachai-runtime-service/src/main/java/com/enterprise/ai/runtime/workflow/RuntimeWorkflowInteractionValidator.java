package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCustomRenderers;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionPresentationPolicy;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionType;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.firstPresent;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.firstText;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.hasRouteEdge;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.hasUnconditionalEdge;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.intValue;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.mapValue;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.text;

/** Validates interaction input, routing and rendering contracts. */
final class RuntimeWorkflowInteractionValidator {

    void validateInteractionNode(GraphSpec.Node node,
                                 List<GraphSpec.Edge> edges,
                                 RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = interactionConfig(node);
        Object rawType = firstPresent(config.get("interactionType"), config.get("mode"), config.get("type"));
        WorkflowInteractionType type;
        try {
            type = WorkflowInteractionType.from(rawType);
            // Unknown tokens collapse to CUSTOM in from(); reject ambiguous legacy mode names.
            if (rawType != null) {
                String normalized = String.valueOf(rawType).trim().replace('-', '_').toUpperCase(Locale.ROOT);
                if ("CONFIRM_ACTION".equals(normalized) || "COLLECT_INPUT".equals(normalized)
                        || "USER_CHOICE".equals(normalized) || "PRESENT_OUTPUT".equals(normalized)
                        || "CUSTOM".equals(normalized) || "REVIEW_EDIT".equals(normalized)) {
                    type = WorkflowInteractionType.valueOf(normalized);
                } else if (StringUtils.hasText(normalized)
                        && type == WorkflowInteractionType.CUSTOM
                        && !"CUSTOM".equals(normalized)) {
                    report.error("GRAPH_INTERACTION_TYPE_UNSUPPORTED", node.getId(),
                            "Unsupported INTERACTION interactionType: " + rawType);
                    return;
                }
            }
        } catch (Exception ex) {
            report.error("GRAPH_INTERACTION_TYPE_UNSUPPORTED", node.getId(),
                    "Unsupported INTERACTION interactionType: " + rawType);
            return;
        }
        if (type == WorkflowInteractionType.REVIEW_EDIT) {
            report.error("GRAPH_INTERACTION_TYPE_UNSUPPORTED", node.getId(),
                    "REVIEW_EDIT is not part of the formal INTERACTION publish contract");
            return;
        }
        validateInteractionPresentation(node, type, config, report);
        switch (type) {
            case COLLECT_INPUT -> validateCollectInputInteraction(node, config, report);
            case USER_CHOICE -> validateUserChoiceInteraction(node, config, edges, report);
            case CONFIRM_ACTION -> validateConfirmActionInteraction(node, config, edges, report);
            case PRESENT_OUTPUT -> validatePresentOutputInteraction(node, config, report);
            case CUSTOM -> validateCustomInteraction(node, config, report);
            default -> report.error("GRAPH_INTERACTION_TYPE_UNSUPPORTED", node.getId(),
                    "Unsupported INTERACTION interactionType: " + type);
        }
        Object schema = config.get("schema");
        if (schema instanceof String schemaText && containsUnsafeMarkup(schemaText)) {
            report.error("GRAPH_INTERACTION_SCHEMA_UNSAFE", node.getId(),
                    "INTERACTION schema must not contain HTML/JS/script markup");
        }
    }

    private Map<String, Object> interactionConfig(GraphSpec.Node node) {
        Map<String, Object> config = node == null || node.getConfig() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(node.getConfig());
        Map<String, Object> nested = mapValue(config.get("interactionConfig"));
        if (!nested.isEmpty()) {
            nested.forEach(config::putIfAbsent);
        }
        return config;
    }

    private void validateCollectInputInteraction(GraphSpec.Node node,
                                                 Map<String, Object> config,
                                                 RuntimeWorkflowReleaseValidationResult.Builder report) {
        Object rawFields = config.get("fields");
        if (!(rawFields instanceof List<?> fields) || fields.isEmpty()) {
            report.error("GRAPH_INTERACTION_FIELDS_REQUIRED", node.getId(),
                    "COLLECT_INPUT requires at least one field");
            return;
        }
        Set<String> keys = new LinkedHashSet<>();
        for (Object item : fields) {
            Map<String, Object> field = mapValue(item);
            String key = firstText(text(field.get("key")), text(field.get("name")));
            if (!StringUtils.hasText(key)) {
                report.error("GRAPH_INTERACTION_FIELD_KEY_EMPTY", node.getId(),
                        "COLLECT_INPUT field key is required");
                continue;
            }
            if (!keys.add(key.toLowerCase(Locale.ROOT))) {
                report.error("GRAPH_INTERACTION_FIELD_KEY_DUPLICATE", node.getId(),
                        "Duplicate COLLECT_INPUT field key: " + key);
            }
            String fieldType = firstText(text(field.get("type")), "string").toLowerCase(Locale.ROOT);
            if (!Set.of("string", "text", "number", "integer", "boolean", "bool", "enum", "select", "multi_select",
                    "object", "array", "date", "datetime").contains(fieldType)) {
                report.error("GRAPH_INTERACTION_FIELD_TYPE_INVALID", node.getId(),
                        "COLLECT_INPUT field type is invalid: " + fieldType);
            }
            if (Set.of("enum", "select", "multi_select").contains(fieldType)
                    && !(field.get("options") instanceof List<?> options && !options.isEmpty())) {
                report.error("GRAPH_INTERACTION_FIELD_OPTIONS_REQUIRED", node.getId(),
                        "COLLECT_INPUT enum/select/multi_select field requires options: " + key);
            }
        }
        String outputAlias = text(config.get("outputAlias"));
        if (StringUtils.hasText(outputAlias) && !outputAlias.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            report.error("GRAPH_INTERACTION_OUTPUT_ALIAS_INVALID", node.getId(),
                    "COLLECT_INPUT outputAlias must be a simple identifier");
        }
    }

    private void validateUserChoiceInteraction(GraphSpec.Node node,
                                               Map<String, Object> config,
                                               List<GraphSpec.Edge> edges,
                                               RuntimeWorkflowReleaseValidationResult.Builder report) {
        List<?> options = config.get("options") instanceof List<?> list ? list : List.of();
        if (options.isEmpty()) {
            report.error("GRAPH_INTERACTION_OPTIONS_REQUIRED", node.getId(),
                    "USER_CHOICE requires at least one option");
            return;
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object item : options) {
            Map<String, Object> option = mapValue(item);
            String value = firstText(text(option.get("value")), text(option.get("id")), text(option.get("key")));
            if (!StringUtils.hasText(value)) {
                report.error("GRAPH_INTERACTION_OPTION_VALUE_EMPTY", node.getId(),
                        "USER_CHOICE option value is required");
                continue;
            }
            if (!values.add(value.toLowerCase(Locale.ROOT))) {
                report.error("GRAPH_INTERACTION_OPTION_VALUE_DUPLICATE", node.getId(),
                        "Duplicate USER_CHOICE option value: " + value);
            }
            String route = firstText(text(option.get("route")), value);
            if (!hasRouteEdge(edges, node.getId(), route)
                    && !hasUnconditionalEdge(edges, node.getId())) {
                report.error("GRAPH_INTERACTION_OPTION_ROUTE_MISSING", node.getId(),
                        "USER_CHOICE option has no matching outgoing edge: " + route);
            }
        }
    }

    private void validateConfirmActionInteraction(GraphSpec.Node node,
                                                  Map<String, Object> config,
                                                  List<GraphSpec.Edge> edges,
                                                  RuntimeWorkflowReleaseValidationResult.Builder report) {
        boolean hasConfirm = hasRouteEdge(edges, node.getId(), "confirm")
                || hasRouteEdge(edges, node.getId(), "approve");
        if (!hasConfirm) {
            report.error("GRAPH_INTERACTION_CONFIRM_ROUTE_MISSING", node.getId(),
                    "CONFIRM_ACTION requires an outgoing confirm/approve route edge");
        }
        boolean rejectOrCancelHasRoute = hasRouteEdge(edges, node.getId(), "reject")
                || hasRouteEdge(edges, node.getId(), "cancel")
                || hasRouteEdge(edges, node.getId(), "deny");
        boolean hasAlways = hasUnconditionalEdge(edges, node.getId());
        if (hasAlways && !rejectOrCancelHasRoute) {
            // reject/cancel must not fall through an always edge into a protected write.
            report.error("GRAPH_INTERACTION_REJECT_FALLBACK_UNSAFE", node.getId(),
                    "CONFIRM_ACTION reject/cancel must not fall through an always edge; declare explicit routes");
        }
        Object protectedAction = firstPresent(config.get("protectedAction"), config.get("actionKey"));
        if (protectedAction != null && !StringUtils.hasText(text(protectedAction))) {
            report.error("GRAPH_INTERACTION_PROTECTED_ACTION_EMPTY", node.getId(),
                    "CONFIRM_ACTION protectedAction cannot be blank when declared");
        }
    }

    private void validatePresentOutputInteraction(GraphSpec.Node node,
                                                  Map<String, Object> config,
                                                  RuntimeWorkflowReleaseValidationResult.Builder report) {
        String component = firstText(text(config.get("component")), text(config.get("renderer")), "detail")
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        if (!Set.of("detail", "card", "table", "list_card", "summary_card", "output_card", "markdown")
                .contains(component)) {
            report.error("GRAPH_INTERACTION_PRESENT_RENDERER_UNSUPPORTED", node.getId(),
                    "PRESENT_OUTPUT renderer/component is not supported: " + component);
        }
        Object blocking = mapValue(config.get("behavior")).get("blocking");
        if (Boolean.TRUE.equals(blocking)) {
            report.error("GRAPH_INTERACTION_PRESENT_MUST_NOT_BLOCK", node.getId(),
                    "PRESENT_OUTPUT must be display-only (blocking=false)");
        }
        if ("list_card".equals(component)) {
            validateListCardRenderSchema(node, config, report);
        }
    }

    private void validateInteractionPresentation(GraphSpec.Node node,
                                                 WorkflowInteractionType type,
                                                 Map<String, Object> config,
                                                 RuntimeWorkflowReleaseValidationResult.Builder report) {
        Object rawPresentation = config.get("presentation");
        if (rawPresentation == null) {
            return;
        }
        if (!(rawPresentation instanceof Map<?, ?>)) {
            report.error("GRAPH_INTERACTION_PRESENTATION_INVALID", node.getId(),
                    "INTERACTION presentation must be an object");
            return;
        }
        Map<String, Object> presentation = mapValue(rawPresentation);
        Object rawMode = presentation.get("mode");
        if (rawMode == null) {
            return;
        }
        String mode = WorkflowInteractionPresentationPolicy.normalizeMode(rawMode);
        if (!StringUtils.hasText(mode)) {
            report.error("GRAPH_INTERACTION_PRESENTATION_MODE_UNSUPPORTED", node.getId(),
                    "Unsupported INTERACTION presentation.mode: " + rawMode);
            return;
        }
        if (type.blocking() && WorkflowInteractionPresentationPolicy.TEXT_ONLY.equals(mode)) {
            report.error("GRAPH_INTERACTION_PRESENTATION_TEXT_ONLY_UNSAFE", node.getId(),
                    "Blocking INTERACTION nodes cannot hide their interactive card");
        }
    }

    private void validateListCardRenderSchema(GraphSpec.Node node,
                                              Map<String, Object> config,
                                              RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> schema = mapValue(config.get("renderSchema"));
        if (schema.isEmpty()) {
            schema = mapValue(config.get("schema"));
        }
        Object initialVisibleCount = schema.get("initialVisibleCount");
        if (initialVisibleCount != null) {
            int count = intValue(initialVisibleCount, -1);
            if (count < 1 || count > 50) {
                report.error("GRAPH_INTERACTION_LIST_CARD_VISIBLE_COUNT_INVALID", node.getId(),
                        "LIST_CARD initialVisibleCount must be between 1 and 50");
            }
        }
        Object fields = schema.get("fields");
        if (fields != null && !(fields instanceof List<?>)) {
            report.error("GRAPH_INTERACTION_LIST_CARD_FIELDS_INVALID", node.getId(),
                    "LIST_CARD fields must be an array");
        }
    }

    private void validateCustomInteraction(GraphSpec.Node node,
                                           Map<String, Object> config,
                                           RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> renderSchema = mapValue(config.get("renderSchema"));
        String rendererKey = firstText(text(renderSchema.get("rendererKey")), text(config.get("rendererKey")));
        if (!WorkflowInteractionCustomRenderers.isSupported(rendererKey)) {
            report.error("GRAPH_INTERACTION_CUSTOM_RENDERER_UNSUPPORTED", node.getId(),
                    "CUSTOM rendererKey is not in the shared whitelist");
            return;
        }
        Object schema = firstPresent(renderSchema.get("schema"), config.get("schema"), renderSchema);
        if (schema == null || (schema instanceof Map<?, ?> map && map.isEmpty())) {
            report.error("GRAPH_INTERACTION_CUSTOM_SCHEMA_REQUIRED", node.getId(),
                    "CUSTOM interaction requires a schema");
            return;
        }
        if (schema instanceof String schemaText && containsUnsafeMarkup(schemaText)) {
            report.error("GRAPH_INTERACTION_SCHEMA_UNSAFE", node.getId(),
                    "CUSTOM schema must not contain HTML/JS/script markup");
        } else if (schema instanceof Map<?, ?> schemaMap) {
            String serialized = String.valueOf(schemaMap);
            if (containsUnsafeMarkup(serialized)) {
                report.error("GRAPH_INTERACTION_SCHEMA_UNSAFE", node.getId(),
                        "CUSTOM schema must not contain HTML/JS/script markup");
            }
        }
    }

    private boolean containsUnsafeMarkup(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("<script")
                || lower.contains("javascript:")
                || lower.contains("onerror=")
                || lower.contains("onload=")
                || lower.contains("<iframe")
                || lower.contains("<img");
    }
}
