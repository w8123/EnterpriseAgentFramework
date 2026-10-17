package com.enterprise.ai.model.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpstreamModelListParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesOpenAiStyleListNewestFirstWithoutDuplicates() throws Exception {
        List<UpstreamModel> models = UpstreamModelListParser.parse(objectMapper.readTree("""
                {"object":"list","data":[
                  {"id":"qwen-plus","object":"model","created":1700000000,"owned_by":"system"},
                  {"id":"qwen3-max","object":"model","created":1760000000,"owned_by":"system"},
                  {"id":"qwen-plus","object":"model","created":1700000000},
                  {"id":"text-embedding-v4","object":"model"}
                ]}
                """));

        assertEquals(List.of("qwen3-max", "qwen-plus", "text-embedding-v4"),
                models.stream().map(UpstreamModel::id).toList());
        assertEquals("system", models.get(0).ownedBy());
        assertEquals(1760000000L, models.get(0).created());
        assertNull(models.get(2).created());
        assertEquals("EMBEDDING", models.get(2).inferredType());
    }

    @Test
    void stripsGeminiModelsPrefix() throws Exception {
        List<UpstreamModel> models = UpstreamModelListParser.parse(objectMapper.readTree("""
                {"models":[{"name":"models/gemini-2.5-pro"},{"name":"models/gemini-embedding-001"}]}
                """));

        assertEquals(List.of("gemini-2.5-pro", "gemini-embedding-001"),
                models.stream().map(UpstreamModel::id).toList());
    }

    @Test
    void infersTypeForFiltering() {
        assertEquals("LLM", UpstreamModelListParser.inferType("deepseek-chat"));
        assertEquals("LLM", UpstreamModelListParser.inferType("qwen-vl-max"));
        assertEquals("EMBEDDING", UpstreamModelListParser.inferType("text-embedding-3-large"));
        assertEquals("RERANKER", UpstreamModelListParser.inferType("gte-rerank-v2"));
        assertEquals("RERANKER", UpstreamModelListParser.inferType("bge-reranker-v2-m3"));
        assertEquals("OTHER", UpstreamModelListParser.inferType("cosyvoice-v2"));
        assertEquals("OTHER", UpstreamModelListParser.inferType("wanx2.1-t2i-turbo"));
        assertEquals("OTHER", UpstreamModelListParser.inferType("whisper-1"));
    }

    @Test
    void rejectsUnknownShape() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> UpstreamModelListParser.parse(objectMapper.readTree("{\"error\":\"nope\"}")));
    }
}
