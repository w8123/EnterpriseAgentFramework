package com.enterprise.ai.model.instance;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProtectedOptionsTest {

    @Test
    void chatProtectedFieldsRejectedInDefaultOptions() {
        Map<String, Object> options = Map.of("model", "gpt-x", "stream", true, "temperature", 0.2);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ModelProtectedOptions.assertAllowed(ModelType.LLM, options, "defaultOptions"));
        assertTrue(ex.getMessage().contains("defaultOptions contains protected fields"));
        assertTrue(ex.getMessage().contains("model"));
        assertTrue(ex.getMessage().contains("stream"));
    }

    @Test
    void chatProtectedFieldsRejectedInRequestOptions() {
        Map<String, Object> options = Map.of(
                "messages", java.util.List.of(),
                "tools", java.util.List.of(),
                "tool_choice", "auto",
                "stream_options", Map.of("include_usage", true));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ModelProtectedOptions.assertAllowed(ModelType.LLM, options));
        assertTrue(ex.getMessage().contains("messages"));
        assertTrue(ex.getMessage().contains("tools"));
        assertTrue(ex.getMessage().contains("tool_choice"));
        assertTrue(ex.getMessage().contains("stream_options"));
    }

    @Test
    void embeddingProtectedFieldsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ModelProtectedOptions.assertAllowed(ModelType.EMBEDDING, Map.of("model", "x", "input", "y")));
        assertTrue(ex.getMessage().contains("model"));
        assertTrue(ex.getMessage().contains("input"));
    }

    @Test
    void rerankProtectedFieldsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ModelProtectedOptions.assertAllowed(ModelType.RERANKER,
                        Map.of("query", "q", "documents", java.util.List.of("a"), "top_n", 3)));
        assertTrue(ex.getMessage().contains("query"));
        assertTrue(ex.getMessage().contains("documents"));
        assertTrue(ex.getMessage().contains("top_n"));
    }

    @Test
    void connectionKeysRejectedInOptions() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ModelProtectedOptions.assertAllowed(ModelType.LLM,
                        Map.of("baseUrl", "https://x", "apiKey", "sk", "path", "/x")));
        assertTrue(ex.getMessage().contains("baseUrl"));
        assertTrue(ex.getMessage().contains("apiKey"));
        assertTrue(ex.getMessage().contains("path"));
    }

    @Test
    void safeOptionsAllowed() {
        assertDoesNotThrow(() -> ModelProtectedOptions.assertAllowed(
                ModelType.LLM, Map.of("temperature", 0.7, "max_tokens", 256)));
        assertDoesNotThrow(() -> ModelProtectedOptions.assertAllowed(
                ModelType.EMBEDDING, Map.of("encoding_format", "float")));
        assertDoesNotThrow(() -> ModelProtectedOptions.assertAllowed(
                ModelType.RERANKER, Map.of("return_documents", true)));
    }

    @Test
    void mergeOptionsShallowKeyMerge() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("temperature", 0.7);
        defaults.put("max_tokens", 100);
        Map<String, Object> overrides = Map.of("max_tokens", 256, "top_p", 0.9);
        Map<String, Object> merged = ModelProtectedOptions.mergeOptions(defaults, overrides);
        assertEquals(0.7, merged.get("temperature"));
        assertEquals(256, merged.get("max_tokens"));
        assertEquals(0.9, merged.get("top_p"));
    }
}
