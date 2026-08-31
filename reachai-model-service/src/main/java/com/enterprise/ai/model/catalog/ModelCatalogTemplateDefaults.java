package com.enterprise.ai.model.catalog;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
class ModelCatalogTemplateDefaults {

    Defaults resolve(String provider, String modelType) {
        String normalizedProvider = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        String baseUrl = switch (normalizedProvider) {
            case "openai" -> "https://api.openai.com/v1";
            case "tongyi" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "anthropic" -> "https://api.anthropic.com/v1";
            case "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai";
            case "deepseek" -> "https://api.deepseek.com";
            case "kimi" -> "https://api.moonshot.ai/v1";
            case "zhipu" -> "https://open.bigmodel.cn/api/paas/v4";
            default -> null;
        };
        if (baseUrl == null) return null;
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("baseUrl", baseUrl);
        if ("EMBEDDING".equals(modelType)) {
            connection.put("embeddingPath", "/embeddings");
        } else if ("RERANKER".equals(modelType)) {
            connection.put("rerankPath", "/rerank");
        } else {
            connection.put("chatPath", "/chat/completions");
        }
        connection.put("authHeader", "Authorization");
        connection.put("authPrefix", "Bearer ");
        List<Map<String, Object>> credentials = List.of(Map.of(
                "key", "apiKey",
                "label", "API Key",
                "required", true,
                "secret", true));
        return new Defaults(connection, credentials, normalizedProvider);
    }

    record Defaults(Map<String, Object> connectionDefaults,
                    List<Map<String, Object>> credentialSchema,
                    String iconKey) {
    }
}
