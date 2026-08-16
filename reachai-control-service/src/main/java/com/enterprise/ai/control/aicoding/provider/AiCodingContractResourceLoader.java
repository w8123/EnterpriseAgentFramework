package com.enterprise.ai.control.aicoding.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AiCodingContractResourceLoader {

    private static final String ROOT = "ai-coding/contracts/";
    private static final Pattern EXTERNAL_SCHEMA_REFERENCE = Pattern.compile(
            "^([A-Za-z0-9._-]+\\.schema\\.json)(?:#.*)?$");

    private final ObjectMapper objectMapper;

    public JsonNode load(String fileName) {
        ClassPathResource resource = new ClassPathResource(ROOT + fileName);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Missing AI Coding contract resource: " + fileName);
        }
        try (var input = resource.getInputStream()) {
            return objectMapper.readTree(input);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Invalid AI Coding contract resource: " + fileName,
                    ex);
        }
    }

    /**
     * Supplies every external JSON Schema resource needed by a task contract,
     * so clients can resolve relative $ref values without guessing server paths.
     */
    public Map<String, JsonNode> referencedSchemas(JsonNode rootSchema) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        collectReferences(rootSchema, result);
        return Map.copyOf(result);
    }

    private void collectReferences(
            JsonNode node,
            Map<String, JsonNode> result) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode reference = node.get("$ref");
            if (reference != null && reference.isTextual()) {
                Matcher matcher = EXTERNAL_SCHEMA_REFERENCE.matcher(reference.asText());
                if (matcher.matches()) {
                    String fileName = matcher.group(1);
                    if (!result.containsKey(fileName)) {
                        JsonNode referenced = load(fileName);
                        result.put(fileName, referenced);
                        collectReferences(referenced, result);
                    }
                }
            }
            node.elements().forEachRemaining(child -> collectReferences(child, result));
            return;
        }
        if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectReferences(child, result));
        }
    }
}
