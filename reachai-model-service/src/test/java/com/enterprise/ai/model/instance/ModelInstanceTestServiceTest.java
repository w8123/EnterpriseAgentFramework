package com.enterprise.ai.model.instance;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.runtime.OpenAiCompatibleRuntimeClient;
import com.enterprise.ai.model.security.CredentialCipher;
import com.enterprise.ai.model.service.ChatResponse;
import com.enterprise.ai.model.service.EmbeddingResponse;
import com.enterprise.ai.model.service.RerankResponse;
import com.enterprise.ai.model.template.ModelTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelInstanceTestServiceTest {

    private ModelInstanceMapper mapper;
    private OpenAiCompatibleRuntimeClient client;
    private CredentialCipher cipher;
    private ModelInstanceService instanceService;
    private ModelInstanceTestService testService;

    @BeforeEach
    void setUp() {
        mapper = mock(ModelInstanceMapper.class);
        client = mock(OpenAiCompatibleRuntimeClient.class);
        cipher = new CredentialCipher("unit-test-secret-for-model-center");
        instanceService = new ModelInstanceService(mapper, mock(ModelTemplateService.class), new ObjectMapper(), cipher);
        testService = new ModelInstanceTestService(instanceService, client);
        when(mapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void disabledInstanceCanBeTested() {
        ModelInstanceEntity entity = savedEntity("id-1", ModelInstanceStatus.DISABLED);
        when(mapper.selectById("id-1")).thenReturn(entity);
        when(client.chat(any(), any())).thenReturn(ChatResponse.builder().content("hello").build());

        ModelInstanceTestResponse response = testService.testSaved("id-1");

        assertTrue(response.isSuccess());
        assertEquals(ModelTestStatus.SUCCESS.name(), response.getLastTestStatus());
        ArgumentCaptor<ModelInstanceEntity> captor = ArgumentCaptor.forClass(ModelInstanceEntity.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(ModelTestStatus.SUCCESS.name(), captor.getValue().getLastTestStatus());
    }

    @Test
    void failureRecordsFailedStatus() {
        ModelInstanceEntity entity = savedEntity("id-1", ModelInstanceStatus.ACTIVE);
        when(mapper.selectById("id-1")).thenReturn(entity);
        when(client.chat(any(), any())).thenThrow(new BizException(502, "provider down sk-abcdefghi123"));

        ModelInstanceTestResponse response = testService.testSaved("id-1");

        assertFalse(response.isSuccess());
        assertEquals(ModelTestStatus.FAILED.name(), response.getLastTestStatus());
        assertFalse(response.getMessage().contains("sk-abcdefghi123"));
        assertTrue(response.getMessage().contains("sk-******") || response.getMessage().contains("provider down"));
        assertEquals(ModelTestStatus.FAILED.name(), entity.getLastTestStatus());
    }

    @Test
    void archivedCannotBeTested() {
        ModelInstanceEntity entity = savedEntity("id-1", ModelInstanceStatus.ARCHIVED);
        when(mapper.selectById("id-1")).thenReturn(entity);

        BizException ex = assertThrows(BizException.class, () -> testService.testSaved("id-1"));
        assertTrue(ex.getMessage().toLowerCase().contains("archived"));
        verify(client, never()).chat(any(), any());
    }

    @Test
    void draftMergesMaskedConnectionAndDoesNotUpdateDb() {
        ModelInstanceEntity entity = savedEntity("id-1", ModelInstanceStatus.ACTIVE);
        when(mapper.selectById("id-1")).thenReturn(entity);
        when(client.chat(any(), any())).thenReturn(ChatResponse.builder().content("ok").build());

        ModelInstanceRequest draft = new ModelInstanceRequest();
        draft.setId("id-1");
        draft.setConnection(Map.of(
                "baseUrl", "https://api.openai.com/v1",
                "apiKey", "sk-li******efgh"));

        ModelInstanceTestResponse response = testService.testDraft(draft);

        assertTrue(response.isSuccess());
        ArgumentCaptor<ModelInstanceRuntime> runtimeCaptor = ArgumentCaptor.forClass(ModelInstanceRuntime.class);
        verify(client).chat(runtimeCaptor.capture(), any());
        assertEquals("sk-live-abcdefgh", runtimeCaptor.getValue().getConnectionConfig().get("apiKey"));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void pureDraftDoesNotPersistTestResult() {
        when(client.chat(any(), any())).thenReturn(ChatResponse.builder().content("ok").build());

        ModelInstanceRequest draft = new ModelInstanceRequest();
        draft.setName("Draft");
        draft.setProvider("openai");
        draft.setModelType(ModelType.LLM);
        draft.setModelName("gpt-4.1");
        draft.setConnection(Map.of(
                "baseUrl", "https://api.openai.com/v1",
                "apiKey", "sk-draft-key-1234"));

        ModelInstanceTestResponse response = testService.testDraft(draft);
        assertTrue(response.isSuccess());
        verify(mapper, never()).updateById(any());
        verify(mapper, never()).selectById(anyString());
    }

    @Test
    void embeddingEmptyVectorFails() {
        ModelInstanceEntity entity = savedEntity("emb-1", ModelInstanceStatus.ACTIVE);
        entity.setModelType(ModelType.EMBEDDING.name());
        entity.setModelName("text-embedding-3-large");
        when(mapper.selectById("emb-1")).thenReturn(entity);
        when(client.embed(any(), any())).thenReturn(EmbeddingResponse.builder()
                .dimension(0)
                .embeddings(List.of(List.of()))
                .build());

        ModelInstanceTestResponse response = testService.testSaved("emb-1");
        assertFalse(response.isSuccess());
        assertTrue(response.getMessage().toLowerCase().contains("embedding")
                || response.getMessage().contains("dimension")
                || response.getMessage().contains("vector"));
    }

    @Test
    void rerankEmptyResultsFails() {
        ModelInstanceEntity entity = savedEntity("rr-1", ModelInstanceStatus.ACTIVE);
        entity.setModelType(ModelType.RERANKER.name());
        entity.setModelName("rerank-model");
        when(mapper.selectById("rr-1")).thenReturn(entity);
        when(client.rerank(any(), any())).thenReturn(RerankResponse.builder()
                .results(List.of())
                .build());

        ModelInstanceTestResponse response = testService.testSaved("rr-1");
        assertFalse(response.isSuccess());
        assertTrue(response.getMessage().toLowerCase().contains("rerank"));
    }

    @Test
    void draftMaskedKeyWithNewHostReturnsSuccessFalse() {
        ModelInstanceEntity entity = savedEntity("id-1", ModelInstanceStatus.ACTIVE);
        when(mapper.selectById("id-1")).thenReturn(entity);

        ModelInstanceRequest draft = new ModelInstanceRequest();
        draft.setId("id-1");
        draft.setConnection(Map.of(
                "baseUrl", "https://api.deepseek.com",
                "apiKey", "sk-li******efgh"));

        ModelInstanceTestResponse response = testService.testDraft(draft);
        assertFalse(response.isSuccess());
        assertTrue(response.getMessage().contains("BaseURL authority changed")
                || response.getMessage().contains("apiKey"));
        verify(client, never()).chat(any(), any());
    }

    private ModelInstanceEntity savedEntity(String id, ModelInstanceStatus status) {
        ModelInstanceEntity entity = new ModelInstanceEntity();
        entity.setId(id);
        entity.setName("LLM");
        entity.setProvider("openai");
        entity.setModelType(ModelType.LLM.name());
        entity.setModelName("gpt-4.1");
        entity.setProtocol(ModelProtocol.OPENAI_COMPATIBLE.name());
        entity.setProjectScopeKey("");
        entity.setConnectionConfigJson(cipher.encrypt(
                "{\"baseUrl\":\"https://api.openai.com/v1\",\"apiKey\":\"sk-live-abcdefgh\"}"));
        entity.setDefaultOptionsJson("{}");
        entity.setParamsSchemaJson("[]");
        entity.setStatus(status.name());
        entity.setLastTestStatus(ModelTestStatus.UNKNOWN.name());
        return entity;
    }
}
