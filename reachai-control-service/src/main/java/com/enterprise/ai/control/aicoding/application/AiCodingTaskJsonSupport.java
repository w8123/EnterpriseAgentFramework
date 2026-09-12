package com.enterprise.ai.control.aicoding.application;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.List;
import java.util.Objects;

/** AI Coding 的 JSON 读取、规范化和内容指纹；保留既有空值与损坏历史值的读取约定。 */
@Component
@RequiredArgsConstructor
class AiCodingTaskJsonSupport {
    private final ObjectMapper objectMapper;

    String canonicalJson(JsonNode value) {
        return writeJson(canonicalize(value));
    }

    JsonNode canonicalize(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) {
            return value;
        }
        if (value.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            value.forEach(item -> array.add(canonicalize(item)));
            return array;
        }
        ObjectNode object = objectMapper.createObjectNode();
        List<String> names = new ArrayList<>();
        value.fieldNames().forEachRemaining(names::add);
        names.stream().sorted().forEach(name ->
                object.set(name, canonicalize(value.get(name))));
        return object;
    }

    String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(
                    value == null ? objectMapper.createObjectNode() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                    "AI Coding value is not JSON serializable",
                    ex);
        }
    }

    JsonNode readNode(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return objectMapper.createObjectNode();
        }
    }

    JsonNode readJsonOrNull(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    boolean sameJson(JsonNode left, JsonNode right) {
        JsonNode normalizedLeft = left == null || left.isNull()
                ? null
                : canonicalize(left);
        JsonNode normalizedRight = right == null || right.isNull()
                ? null
                : canonicalize(right);
        return Objects.equals(normalizedLeft, normalizedRight);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
