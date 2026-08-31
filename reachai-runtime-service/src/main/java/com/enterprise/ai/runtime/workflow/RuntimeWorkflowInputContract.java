package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.graph.GraphSpecUserInputContract;
import com.enterprise.ai.agent.graph.GraphSpecUserInputContract.InputField;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The public input contract shared by PAGE_ASSISTANT AI Coding, Studio and Runtime.
 *
 * <p>The contract deliberately lives beside Workflow semantics rather than in a Vue
 * component or an Agent Skill package example.  A generated Workflow is therefore validated by the
 * same rules that make {@code params.question} available at execution time.</p>
 */
public final class RuntimeWorkflowInputContract {

    public static final String PAGE_ASSISTANT_OUTPUT_ALIAS = "params";
    public static final String DEFAULT_FIELD_NAME = "question";
    public static final String DEFAULT_FIELD_SOURCE = "input.message";

    private static final Set<String> SUPPORTED_FIELD_TYPES = Set.of(
            "string", "text", "number", "integer", "boolean", "bool", "object", "array", "file");

    private RuntimeWorkflowInputContract() {
    }

    /**
     * A valid, editable PAGE_ASSISTANT starter. It is intentionally only an input
     * node: AI Coding is expected to add the selected PAGE_ACTION / TOOL and
     * ANSWER nodes instead of inheriting a hidden fake action.
     */
    public static GraphSpec pageAssistantStarterGraph() {
        GraphSpec.Node input = new GraphSpec.Node();
        input.setId("user_input");
        input.setType(AgentGraphNodeType.USER_INPUT.type());
        input.setName("用户问题");
        input.setConfig(Map.of(
                "outputAlias", PAGE_ASSISTANT_OUTPUT_ALIAS,
                "fields", List.of(Map.of(
                        "name", DEFAULT_FIELD_NAME,
                        "type", "string",
                        "required", true,
                        "description", "用户问题",
                        "source", DEFAULT_FIELD_SOURCE)),
                "userInputConfig", Map.of(
                        "outputAlias", PAGE_ASSISTANT_OUTPUT_ALIAS,
                        "fields", List.of(Map.of(
                                "name", DEFAULT_FIELD_NAME,
                                "type", "string",
                                "required", true,
                                "description", "用户问题",
                                "source", DEFAULT_FIELD_SOURCE)))));

        GraphSpec graph = new GraphSpec();
        graph.setSchemaVersion(2);
        graph.setInputSchema(GraphSpecUserInputContract.inputSchema(List.of(defaultQuestionField())));
        graph.setNodes(List.of(input));
        graph.setEdges(List.of());
        graph.setEntryNodeId(input.getId());
        graph.setExitNodeIds(List.of(input.getId()));
        return graph;
    }

    /** Validates the PAGE_ASSISTANT-specific portion of an otherwise normal GraphSpec. */
    public static void validatePageAssistant(GraphSpec graph,
                                             RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (graph == null) {
            return;
        }
        List<GraphSpec.Node> inputs = (graph.getNodes() == null ? List.<GraphSpec.Node>of() : graph.getNodes())
                .stream()
                .filter(node -> node != null && AgentGraphNodeType.USER_INPUT.type()
                        .equals(AgentGraphNodeType.normalize(node.getType())))
                .toList();
        if (inputs.isEmpty()) {
            report.error("PAGE_ASSISTANT_USER_INPUT_REQUIRED", null,
                    "PAGE_ASSISTANT requires exactly one USER_INPUT entry node");
            return;
        }
        if (inputs.size() > 1) {
            report.error("PAGE_ASSISTANT_USER_INPUT_DUPLICATE", null,
                    "PAGE_ASSISTANT must declare exactly one USER_INPUT node");
            return;
        }

        GraphSpec.Node input = inputs.get(0);
        if (!StringUtils.hasText(graph.getEntryNodeId())
                || !input.getId().equals(graph.getEntryNodeId().trim())) {
            report.error("PAGE_ASSISTANT_USER_INPUT_ENTRY_REQUIRED", input.getId(),
                    "PAGE_ASSISTANT graphSpec.entryNodeId must point to its USER_INPUT node");
        }

        String alias = GraphSpecUserInputContract.outputAlias(input);
        if (!PAGE_ASSISTANT_OUTPUT_ALIAS.equals(alias)) {
            report.error("PAGE_ASSISTANT_INPUT_ALIAS_INVALID", input.getId(),
                    "PAGE_ASSISTANT USER_INPUT outputAlias must be params");
        }

        List<InputField> fields = GraphSpecUserInputContract.inputFields(input);
        if (fields.isEmpty()) {
            report.error("PAGE_ASSISTANT_INPUT_FIELDS_REQUIRED", input.getId(),
                    "PAGE_ASSISTANT USER_INPUT requires at least one input field");
        }
        Set<String> names = new LinkedHashSet<>();
        for (InputField field : fields) {
            if (!StringUtils.hasText(field.name())) {
                report.error("PAGE_ASSISTANT_INPUT_FIELD_NAME_EMPTY", input.getId(),
                        "PAGE_ASSISTANT input field name is required");
                continue;
            }
            if (!field.name().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                report.error("PAGE_ASSISTANT_INPUT_FIELD_NAME_INVALID", input.getId(),
                        "PAGE_ASSISTANT input field name must be a simple identifier: " + field.name());
            }
            if (!names.add(field.name().toLowerCase(Locale.ROOT))) {
                report.error("PAGE_ASSISTANT_INPUT_FIELD_DUPLICATE", input.getId(),
                        "Duplicate PAGE_ASSISTANT input field: " + field.name());
            }
            if (!SUPPORTED_FIELD_TYPES.contains(GraphSpecUserInputContract.normalizeType(field.type()))) {
                report.error("PAGE_ASSISTANT_INPUT_FIELD_TYPE_INVALID", input.getId(),
                        "Unsupported PAGE_ASSISTANT input field type: " + field.type());
            }
            if (!StringUtils.hasText(field.source())) {
                report.error("PAGE_ASSISTANT_INPUT_FIELD_SOURCE_REQUIRED", input.getId(),
                        "PAGE_ASSISTANT input field source is required: " + field.name());
            }
        }

        validateInputSchema(graph.getInputSchema(), fields, input.getId(), report);
    }

    private static void validateInputSchema(Map<String, Object> schema,
                                            List<InputField> fields,
                                            String nodeId,
                                            RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (schema == null || schema.isEmpty()) {
            report.error("PAGE_ASSISTANT_INPUT_SCHEMA_REQUIRED", nodeId,
                    "PAGE_ASSISTANT graphSpec.inputSchema must declare the USER_INPUT fields");
            return;
        }
        if (!"object".equalsIgnoreCase(text(schema.get("type")))) {
            report.error("PAGE_ASSISTANT_INPUT_SCHEMA_TYPE_INVALID", nodeId,
                    "PAGE_ASSISTANT graphSpec.inputSchema.type must be object");
        }
        Map<String, Object> properties = mutableMap(schema.get("properties"));
        Set<String> required = stringSet(schema.get("required"));
        for (InputField field : fields) {
            if (!StringUtils.hasText(field.name())) {
                continue;
            }
            Map<String, Object> property = mutableMap(properties.get(field.name()));
            if (property.isEmpty()) {
                report.error("PAGE_ASSISTANT_INPUT_SCHEMA_FIELD_MISSING", nodeId,
                        "graphSpec.inputSchema.properties is missing field: " + field.name());
                continue;
            }
            if (!GraphSpecUserInputContract.jsonSchemaType(field.type())
                    .equalsIgnoreCase(text(property.get("type")))) {
                report.error("PAGE_ASSISTANT_INPUT_SCHEMA_FIELD_TYPE_MISMATCH", nodeId,
                        "graphSpec.inputSchema field type does not match USER_INPUT field: " + field.name());
            }
            if (field.required() != required.contains(field.name())) {
                report.error("PAGE_ASSISTANT_INPUT_SCHEMA_REQUIRED_MISMATCH", nodeId,
                        "graphSpec.inputSchema required list does not match USER_INPUT field: " + field.name());
            }
        }
    }

    private static InputField defaultQuestionField() {
        return new InputField(DEFAULT_FIELD_NAME, "string", true, DEFAULT_FIELD_SOURCE, null);
    }

    private static Map<String, Object> mutableMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object item : list) {
            if (item != null && StringUtils.hasText(String.valueOf(item))) {
                values.add(String.valueOf(item).trim());
            }
        }
        return values;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

}
