package com.enterprise.ai.control.mcp.application.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business-method contracts describe a request object both as {@code pageReqVO: object} and as flat
 * {@code "pageReqVO.productId"} keys. AI tools then send the flat keys, which the method cannot bind.
 * On the MCP surface the flat keys are folded into the object's properties, and flat arguments that
 * still arrive are folded back into the object before execution.
 */
public final class McpDottedArgumentShape {

    private McpDottedArgumentShape() {
    }

    /** A copy of the schema with {@code "a.b"} properties moved under the declared object property {@code a}. */
    public static JsonNode nest(JsonNode schema) {
        if (schema == null || !schema.isObject() || !schema.path("properties").isObject()) {
            return schema;
        }
        ObjectNode copy = ((ObjectNode) schema).deepCopy();
        ObjectNode properties = (ObjectNode) copy.get("properties");
        List<String> dotted = new ArrayList<>();
        properties.fieldNames().forEachRemaining(name -> {
            if (parentOf(name, properties) != null) {
                dotted.add(name);
            }
        });
        for (String name : dotted) {
            String parent = parentOf(name, properties);
            ObjectNode parentSchema = (ObjectNode) properties.get(parent);
            ObjectNode nested = parentSchema.has("properties") && parentSchema.get("properties").isObject()
                    ? (ObjectNode) parentSchema.get("properties")
                    : parentSchema.putObject("properties");
            nested.set(name.substring(parent.length() + 1), properties.get(name));
            properties.remove(name);
        }
        if (!dotted.isEmpty() && copy.path("required").isArray()) {
            ArrayNode kept = copy.putArray("required");
            for (JsonNode required : (ArrayNode) ((ObjectNode) schema).get("required")) {
                String name = required.asText();
                String parent = parentOf(name, properties);
                if (dotted.contains(name) && parent != null) {
                    ObjectNode parentSchema = (ObjectNode) properties.get(parent);
                    ArrayNode parentRequired = parentSchema.path("required").isArray()
                            ? (ArrayNode) parentSchema.get("required") : parentSchema.putArray("required");
                    parentRequired.add(name.substring(parent.length() + 1));
                } else {
                    kept.add(required);
                }
            }
        }
        return copy;
    }

    /** Folds flat {@code "a.b"} arguments into the object argument {@code a} declared by the schema. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> unflatten(Map<String, Object> arguments, JsonNode schema) {
        if (arguments == null || arguments.isEmpty() || schema == null || !schema.path("properties").isObject()) {
            return arguments;
        }
        JsonNode properties = schema.get("properties");
        Map<String, Object> shaped = new LinkedHashMap<>(arguments);
        boolean changed = false;
        Iterator<Map.Entry<String, Object>> entries = arguments.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, Object> entry = entries.next();
            String parent = parentOf(entry.getKey(), properties);
            if (parent == null) {
                continue;
            }
            Object existing = shaped.get(parent);
            Map<String, Object> target = existing instanceof Map<?, ?> map
                    ? new LinkedHashMap<>((Map<String, Object>) map)
                    : new LinkedHashMap<>();
            target.putIfAbsent(entry.getKey().substring(parent.length() + 1), entry.getValue());
            shaped.put(parent, target);
            shaped.remove(entry.getKey());
            changed = true;
        }
        return changed ? shaped : arguments;
    }

    /** The declared object property this flat key belongs to, or null. */
    private static String parentOf(String name, JsonNode properties) {
        int dot = name.indexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        String parent = name.substring(0, dot);
        JsonNode parentSchema = properties.get(parent);
        return parentSchema != null && parentSchema.isObject() && "object".equals(parentSchema.path("type").asText())
                ? parent : null;
    }
}
