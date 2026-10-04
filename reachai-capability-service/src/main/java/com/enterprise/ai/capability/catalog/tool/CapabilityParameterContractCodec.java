package com.enterprise.ai.capability.catalog.tool;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One parser for catalog display and owner-side Console validation. */
public final class CapabilityParameterContractCodec {

    private static final TypeReference<List<ToolDefinitionParameter>> PARAMETER_LIST_TYPE = new TypeReference<>() { };
    private static final List<String> JSON_SCHEMA_PARAMETER_METADATA_FIELDS = List.of(
            "default", "defaultValue", "example", "examples", "enum", "const", "format", "pattern",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength",
            "maxLength", "minItems", "maxItems", "sourceHint", "dictType", "sensitive");

    private CapabilityParameterContractCodec() {
    }

    public static List<ToolDefinitionParameter> parse(ObjectMapper objectMapper, String parametersJson) {
        if (!StringUtils.hasText(parametersJson)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(parametersJson);
            if (root.isArray()) {
                return objectMapper.convertValue(root, PARAMETER_LIST_TYPE);
            }
            if (isJsonSchemaObject(root)) {
                return parseJsonSchemaProperties(objectMapper, root);
            }
            throw new IllegalArgumentException("tool parameters json must be an array or JSON Schema object");
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid tool parameters json", ex);
        }
    }

    /**
     * Builds the owner-side logical input tree without mutating the raw catalog
     * declaration. SDK registrations intentionally retain parallel dotted names
     * for source/hash/diff; Console display and invocation share this derived
     * representation instead.
     */
    public static List<ToolDefinitionParameter> logicalTree(List<ToolDefinitionParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) return List.of();
        Map<String, LogicalNode> roots = new LinkedHashMap<>();
        for (ToolDefinitionParameter parameter : parameters) {
            if (parameter == null || !StringUtils.hasText(parameter.name())) continue;
            LogicalNode node = addPath(roots, parameter.name().trim());
            node.parameter = parameter;
            attachChildren(node, parameter.children());
        }
        List<ToolDefinitionParameter> result = new ArrayList<>();
        for (LogicalNode root : roots.values()) result.add(root.toParameter());
        return List.copyOf(result);
    }

    private static LogicalNode addPath(Map<String, LogicalNode> roots, String path) {
        String[] segments = path.split("\\.");
        LogicalNode current = roots.computeIfAbsent(segments[0], LogicalNode::synthetic);
        for (int index = 1; index < segments.length; index++) {
            if (!StringUtils.hasText(segments[index])) continue;
            current = current.children.computeIfAbsent(segments[index], LogicalNode::synthetic);
        }
        return current;
    }

    private static void attachChildren(LogicalNode parent, List<ToolDefinitionParameter> parameters) {
        if (parameters == null) return;
        for (ToolDefinitionParameter parameter : parameters) {
            if (parameter == null || !StringUtils.hasText(parameter.name())) continue;
            LogicalNode child = addPath(parent.children, parameter.name().trim());
            child.parameter = parameter;
            attachChildren(child, parameter.children());
        }
    }

    private static boolean isJsonSchemaObject(JsonNode node) {
        if (node == null || !node.isObject()) return false;
        JsonNode type = node.get("type");
        return node.has("properties") || (type != null && type.isTextual() && "object".equals(type.asText()));
    }

    private static List<ToolDefinitionParameter> parseJsonSchemaProperties(ObjectMapper objectMapper, JsonNode schema) {
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) return List.of();
        Set<String> requiredNames = requiredNames(schema.get("required"));
        List<ToolDefinitionParameter> parameters = new ArrayList<>();
        properties.properties().forEach(property -> {
            JsonNode propertySchema = property.getValue();
            parameters.add(new ToolDefinitionParameter(property.getKey(), type(propertySchema),
                    firstText(propertySchema, "description", "title"), requiredNames.contains(property.getKey()),
                    firstText(propertySchema, "location", "in"), children(objectMapper, propertySchema),
                    metadata(objectMapper, propertySchema)));
        });
        return List.copyOf(parameters);
    }

    private static Set<String> requiredNames(JsonNode required) {
        if (required == null || !required.isArray()) return Set.of();
        Set<String> names = new HashSet<>();
        required.forEach(item -> { if (item.isTextual()) names.add(item.asText()); });
        return names;
    }

    private static List<ToolDefinitionParameter> children(ObjectMapper objectMapper, JsonNode schema) {
        if (schema == null || !schema.isObject()) return List.of();
        JsonNode childSchema = "array".equals(type(schema)) ? schema.get("items") : schema;
        return isJsonSchemaObject(childSchema) ? parseJsonSchemaProperties(objectMapper, childSchema) : List.of();
    }

    private static Object metadata(ObjectMapper objectMapper, JsonNode schema) {
        if (schema == null || !schema.isObject()) return null;
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : JSON_SCHEMA_PARAMETER_METADATA_FIELDS) {
            JsonNode value = schema.get(field);
            if (value != null && !value.isNull()) values.put(field, objectMapper.convertValue(value, Object.class));
        }
        JsonNode additionalProperties = schema.get("additionalProperties");
        if (additionalProperties != null && additionalProperties.isBoolean()) {
            // Absence is intentionally distinct from false: declarations without an
            // object shape are open, while JSON Schema may opt into a closed object.
            values.put("openObject", additionalProperties.booleanValue());
        }
        if ("array".equals(type(schema))) {
            JsonNode items = schema.get("items");
            if (items != null && items.isObject()) {
                values.put("itemsType", type(items));
                Object itemMetadata = metadata(objectMapper, items);
                if (itemMetadata instanceof Map<?, ?> itemValues && !itemValues.isEmpty()) {
                    values.put("itemsMetadata", itemValues);
                }
            }
        }
        return values.isEmpty() ? null : values;
    }

    private static String type(JsonNode schema) {
        if (schema == null || !schema.isObject()) return "object";
        JsonNode type = schema.get("type");
        if (type != null && type.isTextual() && StringUtils.hasText(type.asText())) return type.asText();
        if (type != null && type.isArray()) {
            for (JsonNode candidate : type) {
                if (candidate.isTextual() && !"null".equals(candidate.asText())) return candidate.asText();
            }
        }
        if (schema.has("properties")) return "object";
        if (schema.has("items")) return "array";
        return "object";
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) return null;
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) return value.asText();
        }
        return null;
    }

    private static final class LogicalNode {
        private final String name;
        private ToolDefinitionParameter parameter;
        private final Map<String, LogicalNode> children = new LinkedHashMap<>();

        private LogicalNode(String name, ToolDefinitionParameter parameter) {
            this.name = name;
            this.parameter = parameter;
        }

        private static LogicalNode synthetic(String name) {
            return new LogicalNode(name, new ToolDefinitionParameter(name, "object", null, false, null, List.of(), null));
        }

        private ToolDefinitionParameter toParameter() {
            ToolDefinitionParameter source = parameter == null
                    ? new ToolDefinitionParameter(name, "object", null, false, null, List.of(), null)
                    : parameter;
            List<ToolDefinitionParameter> resolvedChildren = new ArrayList<>();
            for (LogicalNode child : children.values()) resolvedChildren.add(child.toParameter());
            return new ToolDefinitionParameter(name, source.type(), source.description(), source.required(), source.location(),
                    resolvedChildren, source.metadata());
        }
    }
}
