package com.enterprise.ai.model.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
class ModelCatalogStructuredParser {

    private final ObjectMapper objectMapper;

    ModelCatalogAnalysis parse(ModelCatalogSourceEntity source, String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode models = locateArray(root);
            if (!models.isArray()) {
                throw new ModelCatalogSyncException(
                        "MODEL_CATALOG_STRUCTURED_SCHEMA_UNSUPPORTED",
                        "Structured official source does not contain a supported model array",
                        true);
            }
            List<ModelCatalogCandidate> candidates = new ArrayList<>();
            for (JsonNode item : models) {
                if (!item.isObject()) continue;
                String modelName = firstText(item, "id", "model_name", "modelName", "name");
                if (!StringUtils.hasText(modelName)) continue;
                if (modelName.startsWith("models/")) modelName = modelName.substring("models/".length());
                String displayName = firstText(item, "display_name", "displayName", "displayNameZh");
                if (!StringUtils.hasText(displayName)) displayName = modelName;
                Map<String, Object> capabilities = safeCapabilities(item);
                candidates.add(new ModelCatalogCandidate(
                        modelName,
                        displayName,
                        inferType(modelName, item),
                        "ACTIVE",
                        firstText(item, "created_at", "published_time", "publishedAt", "release_date"),
                        null,
                        null,
                        null,
                        null,
                        capabilities,
                        modelName,
                        1.0d));
                if (candidates.size() >= 1_000) break;
            }
            String analysis = objectMapper.writeValueAsString(Map.of("models", candidates));
            return new ModelCatalogAnalysis(List.copyOf(candidates), analysis, "DETERMINISTIC");
        } catch (ModelCatalogSyncException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_STRUCTURED_PARSE_FAILED",
                    "Structured official source could not be parsed",
                    true,
                    ex);
        }
    }

    private JsonNode locateArray(JsonNode root) {
        if (root.isArray()) return root;
        for (String field : List.of("data", "models", "items")) {
            if (root.path(field).isArray()) return root.path(field);
        }
        if (root.path("output").path("models").isArray()) return root.path("output").path("models");
        return objectMapper.createArrayNode();
    }

    private String inferType(String modelName, JsonNode item) {
        String explicit = firstText(item, "model_type", "modelType", "type");
        if (StringUtils.hasText(explicit)) {
            String upper = explicit.toUpperCase(Locale.ROOT);
            if (upper.contains("EMBED")) return "EMBEDDING";
            if (upper.contains("RERANK")) return "RERANKER";
            if (upper.contains("LLM") || upper.contains("TEXT") || upper.contains("CHAT")) return "LLM";
        }
        String lower = modelName.toLowerCase(Locale.ROOT);
        if (lower.contains("embedding") || lower.contains("embed-")) return "EMBEDDING";
        if (lower.contains("rerank")) return "RERANKER";
        JsonNode methods = item.path("supportedGenerationMethods");
        if (methods.isArray()) {
            for (JsonNode method : methods) {
                if (method.asText("").toLowerCase(Locale.ROOT).contains("embed")) return "EMBEDDING";
            }
        }
        return "LLM";
    }

    private Map<String, Object> safeCapabilities(JsonNode item) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of(
                "capabilities", "features", "supportedGenerationMethods",
                "inputTokenLimit", "outputTokenLimit", "max_input_tokens", "max_tokens")) {
            JsonNode value = item.get(field);
            if (value != null && !value.isNull()) result.put(field, objectMapper.convertValue(value, Object.class));
        }
        return result;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }
}
