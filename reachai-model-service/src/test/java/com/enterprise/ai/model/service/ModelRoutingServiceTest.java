package com.enterprise.ai.model.service;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ModelInstanceEntity;
import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.instance.ModelInstanceRuntimeCache;
import com.enterprise.ai.model.instance.ModelInstanceService;
import com.enterprise.ai.model.instance.ModelType;
import com.enterprise.ai.model.runtime.OpenAiCompatibleRuntimeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRoutingServiceTest {

    private ModelInstanceService modelInstanceService;
    private OpenAiCompatibleRuntimeClient client;
    private ModelInstanceRuntimeCache runtimeCache;
    private ModelRoutingService routingService;

    @BeforeEach
    void setUp() {
        modelInstanceService = mock(ModelInstanceService.class);
        client = mock(OpenAiCompatibleRuntimeClient.class);
        runtimeCache = new ModelInstanceRuntimeCache(ModelInstanceRuntimeCache.DEFAULT_TTL_MS);
        routingService = new ModelRoutingService(modelInstanceService, runtimeCache, client);
    }

    @Test
    void chatRequiresLlm() {
        stubRuntime("emb-1", ModelType.EMBEDDING);
        BizException ex = assertThrows(BizException.class, () -> routingService.chat(ChatRequest.builder()
                .modelInstanceId("emb-1")
                .messages(List.of())
                .build()));
        assertTrue(ex.getMessage().contains("LLM"));
        verify(client, never()).chat(any(), any());
    }

    @Test
    void embedRequiresEmbedding() {
        stubRuntime("llm-1", ModelType.LLM);
        BizException ex = assertThrows(BizException.class, () -> routingService.embed(EmbeddingRequest.builder()
                .modelInstanceId("llm-1")
                .texts(List.of("hi"))
                .build()));
        assertTrue(ex.getMessage().contains("EMBEDDING"));
        verify(client, never()).embed(any(), any());
    }

    @Test
    void rerankRequiresReranker() {
        stubRuntime("llm-1", ModelType.LLM);
        BizException ex = assertThrows(BizException.class, () -> routingService.rerank(RerankRequest.builder()
                .modelInstanceId("llm-1")
                .query("q")
                .documents(List.of("d"))
                .build()));
        assertTrue(ex.getMessage().contains("RERANKER"));
        verify(client, never()).rerank(any(), any());
    }

    @Test
    void chatDelegatesWhenTypeMatches() {
        ModelInstanceRuntime runtime = stubRuntime("llm-1", ModelType.LLM);
        ChatResponse expected = ChatResponse.builder().content("ok").build();
        when(client.chat(eq(runtime), any())).thenReturn(expected);

        ChatResponse actual = routingService.chat(ChatRequest.builder()
                .modelInstanceId("llm-1")
                .messages(List.of(ChatRequest.ChatMessage.builder().role("user").content("hi").build()))
                .build());

        assertEquals("ok", actual.getContent());
        verify(client).chat(eq(runtime), any());
    }

    @Test
    void reusesCachedRuntimeAfterFirstResolution() {
        ModelInstanceRuntime runtime = stubRuntime("llm-1", ModelType.LLM);
        when(client.chat(eq(runtime), any())).thenReturn(ChatResponse.builder().content("ok").build());
        ChatRequest request = ChatRequest.builder()
                .modelInstanceId("llm-1")
                .messages(List.of(ChatRequest.ChatMessage.builder().role("user").content("hi").build()))
                .build();

        routingService.chat(request);
        routingService.chat(request);

        verify(modelInstanceService).getActiveEntity("llm-1");
        verify(modelInstanceService).toRuntime(any(ModelInstanceEntity.class));
        assertEquals(1L, runtimeCache.hitCount());
    }

    @Test
    void runtimeCacheDefaultTtlIsShortAndHardCappedForMultiInstanceWindow() {
        assertEquals(ModelInstanceRuntimeCache.DEFAULT_TTL_MS, runtimeCache.ttlMs());
        assertEquals(ModelInstanceRuntimeCache.MAX_TTL_MS, ModelInstanceRuntimeCache.clampTtl(120_000L));
        assertTrue(ModelInstanceRuntimeCache.DEFAULT_TTL_MS <= 5_000L);
    }

    private ModelInstanceRuntime stubRuntime(String id, ModelType type) {
        ModelInstanceEntity entity = new ModelInstanceEntity();
        entity.setId(id);
        entity.setStatus("ACTIVE");
        entity.setModelType(type.name());
        when(modelInstanceService.getActiveEntity(id)).thenReturn(entity);
        ModelInstanceRuntime runtime = ModelInstanceRuntime.builder()
                .id(id)
                .modelType(type.name())
                .protocol("OPENAI_COMPATIBLE")
                .connectionConfig(Map.of("baseUrl", "https://api.example.com"))
                .build();
        when(modelInstanceService.toRuntime(entity)).thenReturn(runtime);
        return runtime;
    }
}
