package com.enterprise.ai.model.instance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型调用 options 白名单之外的受保护字段：禁止写入 defaultOptions / request options，
 * 避免覆盖协议根字段或连接配置。
 */
public final class ModelProtectedOptions {

    private static final Set<String> CONNECTION_KEYS = Set.of(
            "baseUrl", "chatPath", "embeddingPath", "rerankPath",
            "apiKey", "authHeader", "authPrefix",
            "path", "token", "secret", "password");

    private static final Set<String> CHAT_KEYS = Set.of(
            "model", "messages", "stream", "stream_options", "tools", "tool_choice");

    private static final Set<String> EMBEDDING_KEYS = Set.of("model", "input");

    private static final Set<String> RERANK_KEYS = Set.of("model", "query", "documents", "top_n");

    private ModelProtectedOptions() {
    }

    public static Set<String> protectedKeys(ModelType type) {
        Set<String> keys = new LinkedHashSet<>(CONNECTION_KEYS);
        if (type == null) {
            return Set.copyOf(keys);
        }
        switch (type) {
            case LLM -> keys.addAll(CHAT_KEYS);
            case EMBEDDING -> keys.addAll(EMBEDDING_KEYS);
            case RERANKER -> keys.addAll(RERANK_KEYS);
        }
        return Set.copyOf(keys);
    }

    public static void assertAllowed(ModelType type, Map<String, Object> options) {
        assertAllowed(type, options, "options");
    }

    public static void assertAllowed(ModelType type, Map<String, Object> options, String fieldName) {
        if (options == null || options.isEmpty()) {
            return;
        }
        Set<String> protectedSet = protectedKeys(type);
        List<String> illegal = new ArrayList<>();
        for (String key : options.keySet()) {
            if (key != null && protectedSet.contains(key)) {
                illegal.add(key);
            }
        }
        if (!illegal.isEmpty()) {
            String label = fieldName == null || fieldName.isBlank() ? "options" : fieldName;
            throw new IllegalArgumentException(label + " contains protected fields: " + String.join(", ", illegal));
        }
    }

    /** 浅合并：defaults 为底，overrides 覆盖同名键。 */
    public static Map<String, Object> mergeOptions(Map<String, Object> defaults, Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (defaults != null) {
            merged.putAll(defaults);
        }
        if (overrides != null) {
            merged.putAll(overrides);
        }
        return merged;
    }
}
