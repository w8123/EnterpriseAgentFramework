package com.enterprise.ai.runtime.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
class RuntimeEvalJsonSupport {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST_TYPE = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(sort(objectMapper.valueToTree(value)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Eval JSON cannot be serialized", ex);
        }
    }

    String nullableJson(Object value) {
        return value == null ? null : canonicalJson(value);
    }

    Map<String, Object> readMap(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored Eval JSON object is invalid", ex);
        }
    }

    Map<String, Object> map(Object value) {
        if (value == null) {
            return Map.of();
        }
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    Object readValue(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored Eval JSON value is invalid", ex);
        }
    }

    List<Map<String, Object>> mapList(Object value) {
        if (value == null) {
            return List.of();
        }
        return objectMapper.convertValue(value, MAP_LIST_TYPE);
    }

    String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    Map<String, Object> ordered(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return result;
    }

    private JsonNode sort(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(item -> result.add(sort(item)));
            return result;
        }
        ObjectNode result = objectMapper.createObjectNode();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        names.forEach(name -> result.set(name, sort(node.get(name))));
        return result;
    }
}
