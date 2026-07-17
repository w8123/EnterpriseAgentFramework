package com.enterprise.ai.model.runtime;

import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.RerankRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleRuntimeClientOptionsTest {

    private OpenAiCompatibleRuntimeClient client;

    @BeforeEach
    void setUp() {
        client = new OpenAiCompatibleRuntimeClient(new ObjectMapper());
    }

    @Test
    void buildChatBodyRejectsProtectedDefaultOptions() {
        ModelInstanceRuntime runtime = runtime("LLM", Map.of("model", "hijack", "temperature", 0.1));
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatRequest.ChatMessage.builder().role("user").content("hi").build()))
                .build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client.buildChatBody(runtime, request, false));
        assertTrue(ex.getMessage().contains("model"));
    }

    @Test
    void buildChatBodyRejectsProtectedRequestOptions() {
        ModelInstanceRuntime runtime = runtime("LLM", Map.of("temperature", 0.2));
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatRequest.ChatMessage.builder().role("user").content("hi").build()))
                .options(Map.of("stream", true, "messages", List.of()))
                .build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client.resolveChatOptions(runtime, request));
        assertTrue(ex.getMessage().contains("stream"));
        assertTrue(ex.getMessage().contains("messages"));
    }

    @Test
    void buildChatBodyAppliesSafeOptionsAfterCoreFields() {
        ModelInstanceRuntime runtime = runtime("LLM", Map.of("temperature", 0.5));
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatRequest.ChatMessage.builder().role("user").content("hi").build()))
                .options(Map.of("max_tokens", 128))
                .build();
        ObjectNode body = client.buildChatBody(runtime, request, true);
        assertEquals("gpt-test", body.get("model").asText());
        assertTrue(body.get("stream").asBoolean());
        assertEquals(0.5, body.get("temperature").asDouble());
        assertEquals(128, body.get("max_tokens").asInt());
        assertTrue(body.has("stream_options"));
        assertFalse(body.has("apiKey"));
    }

    @Test
    void buildEmbeddingBodyRejectsProtectedOptions() {
        ModelInstanceRuntime runtime = runtime("EMBEDDING", Map.of("input", "steal", "encoding_format", "float"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client.buildEmbeddingBody(runtime, List.of("hello")));
        assertTrue(ex.getMessage().contains("input"));
    }

    @Test
    void buildRerankBodyRejectsProtectedOptionsAndIgnoresPathInOptions() {
        ModelInstanceRuntime runtime = runtime("RERANKER", Map.of("path", "/evil", "return_documents", true));
        RerankRequest request = RerankRequest.builder()
                .query("q")
                .documents(List.of("a"))
                .options(Map.of("top_n", 1))
                .build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client.buildRerankBody(runtime, request));
        assertTrue(ex.getMessage().contains("path") || ex.getMessage().contains("top_n"));
    }

    @Test
    void buildRerankBodyAllowsSafeOptions() {
        ModelInstanceRuntime runtime = runtime("RERANKER", Map.of("return_documents", true));
        RerankRequest request = RerankRequest.builder()
                .query("q")
                .documents(List.of("a"))
                .topN(2)
                .build();
        ObjectNode body = client.buildRerankBody(runtime, request);
        assertEquals("gpt-test", body.get("model").asText());
        assertEquals("q", body.get("query").asText());
        assertEquals(2, body.get("top_n").asInt());
        assertTrue(body.get("return_documents").asBoolean());
        assertFalse(body.has("path"));
    }

    private static ModelInstanceRuntime runtime(String modelType, Map<String, Object> defaultOptions) {
        return ModelInstanceRuntime.builder()
                .id("rt-1")
                .name("test")
                .provider("openai")
                .modelType(modelType)
                .modelName("gpt-test")
                .protocol("OPENAI_COMPATIBLE")
                .connectionConfig(Map.of("baseUrl", "https://api.openai.com/v1", "apiKey", "sk-test"))
                .defaultOptions(defaultOptions)
                .build();
    }
}
