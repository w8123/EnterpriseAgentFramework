package com.enterprise.ai.model.instance;

import com.enterprise.ai.common.exception.BizException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.enterprise.ai.model.runtime.OpenAiCompatibleRuntimeClient;
import com.enterprise.ai.model.security.CredentialCipher;
import com.enterprise.ai.model.service.ChatResponse;
import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.EmbeddingResponse;
import com.enterprise.ai.model.service.RerankResponse;
import com.enterprise.ai.model.template.ModelTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
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

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ModelInstanceEntity.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(ModelInstanceMapper.class);
        client = mock(OpenAiCompatibleRuntimeClient.class);
        cipher = new CredentialCipher("unit-test-secret-for-model-center");
        instanceService = new ModelInstanceService(mapper, mock(ModelTemplateService.class), new ObjectMapper(), cipher,
                new ModelInstanceRuntimeCache(ModelInstanceRuntimeCache.DEFAULT_TTL_MS));
        testService = new ModelInstanceTestService(instanceService, client);
        when(mapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void disabledInstanceCanBeTested() {
        ModelInstanceEntity entity = savedEntity("id-1", ModelInstanceStatus.DISABLED);
        when(mapper.selectById("id-1")).thenReturn(entity);
        when(client.chat(any(), any(), any(Duration.class))).thenReturn(ChatResponse.builder().content("hello").build());

        ModelInstanceTestResponse response = testService.testSaved("id-1");

        assertTrue(response.isSuccess());
        assertEquals(ModelTestStatus.SUCCESS.name(), response.getLastTestStatus());
        verify(mapper).update(isNull(), any());
        assertEquals(ModelTestStatus.SUCCESS.name(), entity.getLastTestStatus());
    }

    @Test
    void failureRecordsFailedStatus() {
        ModelInstanceEntity entity = savedEntity("id-1", ModelInstanceStatus.ACTIVE);
        when(mapper.selectById("id-1")).thenReturn(entity);
        when(client.chat(any(), any(), any(Duration.class))).thenThrow(new BizException(502, "provider down sk-abcdefghi123"));

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
        verify(client, never()).chat(any(), any(), any(Duration.class));
    }

    @Test
    void draftMergesMaskedConnectionAndDoesNotUpdateDb() {
        ModelInstanceEntity entity = savedEntity("id-1", ModelInstanceStatus.ACTIVE);
        when(mapper.selectById("id-1")).thenReturn(entity);
        when(client.chat(any(), any(), any(Duration.class))).thenReturn(ChatResponse.builder().content("ok").build());

        ModelInstanceRequest draft = new ModelInstanceRequest();
        draft.setId("id-1");
        draft.setConnection(Map.of(
                "baseUrl", "https://api.openai.com/v1",
                "apiKey", "sk-li******efgh"));

        ModelInstanceTestResponse response = testService.testDraft(draft);

        assertTrue(response.isSuccess());
        ArgumentCaptor<ModelInstanceRuntime> runtimeCaptor = ArgumentCaptor.forClass(ModelInstanceRuntime.class);
        verify(client).chat(runtimeCaptor.capture(), any(), any(Duration.class));
        assertEquals("sk-live-abcdefgh", runtimeCaptor.getValue().getConnectionConfig().get("apiKey"));
        verify(mapper, never()).updateById(any());
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void pureDraftDoesNotPersistTestResult() {
        when(client.chat(any(), any(), any(Duration.class))).thenReturn(ChatResponse.builder().content("ok").build());

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
        verify(mapper, never()).update(any(), any());
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
        verify(client, never()).chat(any(), any(), any(Duration.class));
    }

    @Test
    void llmTestHasSmallIndependentBudgetWithoutMutatingStoredDefaults() {
        ModelInstanceEntity entity = savedEntity("budget-1", ModelInstanceStatus.ACTIVE);
        String originalOptions = "{\"max_tokens\":8192,\"temperature\":0.7}";
        entity.setDefaultOptionsJson(originalOptions);
        when(mapper.selectById("budget-1")).thenReturn(entity);
        when(client.chat(any(), any(), any(Duration.class))).thenReturn(ChatResponse.builder().content("OK").build());

        assertTrue(testService.testSaved("budget-1").isSuccess());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(client).chat(any(), request.capture(), timeout.capture());
        assertEquals(Duration.ofSeconds(30), timeout.getValue());
        assertEquals(128, request.getValue().getOptions().get("max_tokens"));
        assertTrue(request.getValue().getMessages().get(0).getContent().contains("exactly OK"));
        assertEquals(originalOptions, entity.getDefaultOptionsJson());
        assertFalse(request.getValue().getOptions().containsKey("thinking"));
        verify(client, never()).chat(any(), any());
    }

    @Test
    void officialDeepSeekV4TestOnlyDisablesThinkingWithoutChangingDefaults() {
        ModelInstanceEntity entity = savedEntity("deepseek-test", ModelInstanceStatus.ACTIVE);
        entity.setProvider("deepseek");
        entity.setModelName("deepseek-v4-flash");
        entity.setConnectionConfigJson(cipher.encrypt("{\"baseUrl\":\"https://api.deepseek.com/v1\",\"apiKey\":\"unit-test-key\"}"));
        String originalOptions = "{\"max_tokens\":8192,\"thinking\":{\"type\":\"enabled\"}}";
        entity.setDefaultOptionsJson(originalOptions);
        when(mapper.selectById("deepseek-test")).thenReturn(entity);
        when(client.chat(any(), any(), any(Duration.class))).thenReturn(ChatResponse.builder().content("OK").build());

        assertTrue(testService.testSaved("deepseek-test").isSuccess());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        ArgumentCaptor<ModelInstanceRuntime> runtime = ArgumentCaptor.forClass(ModelInstanceRuntime.class);
        verify(client).chat(runtime.capture(), request.capture(), any(Duration.class));
        assertEquals(Map.of("type", "disabled"), request.getValue().getOptions().get("thinking"));
        assertEquals(Map.of("type", "enabled"), runtime.getValue().getDefaultOptions().get("thinking"));
        assertEquals(originalOptions, entity.getDefaultOptionsJson());
    }

    @Test
    void thirdPartyDeepSeekEndpointDoesNotReceiveGuessedThinkingOverride() {
        ModelInstanceEntity entity = savedEntity("third-party", ModelInstanceStatus.ACTIVE);
        entity.setProvider("deepseek");
        entity.setModelName("deepseek-v4-flash");
        entity.setConnectionConfigJson(cipher.encrypt("{\"baseUrl\":\"https://gateway.example/v1\",\"apiKey\":\"unit-test-key\"}"));
        when(mapper.selectById("third-party")).thenReturn(entity);
        when(client.chat(any(), any(), any(Duration.class))).thenReturn(ChatResponse.builder().content("OK").build());

        assertTrue(testService.testSaved("third-party").isSuccess());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(client).chat(any(), request.capture(), any(Duration.class));
        assertFalse(request.getValue().getOptions().containsKey("thinking"));
    }

    @Test
    void llmTestHonorsConfiguredCompletionTokenOptionName() {
        ModelInstanceEntity entity = savedEntity("completion-budget", ModelInstanceStatus.ACTIVE);
        entity.setDefaultOptionsJson("{\"max_completion_tokens\":8192}");
        when(mapper.selectById("completion-budget")).thenReturn(entity);
        when(client.chat(any(), any(), any(Duration.class))).thenReturn(ChatResponse.builder().content("OK").build());

        assertTrue(testService.testSaved("completion-budget").isSuccess());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(client).chat(any(), request.capture(), any(Duration.class));
        assertEquals(128, request.getValue().getOptions().get("max_completion_tokens"));
        assertFalse(request.getValue().getOptions().containsKey("max_tokens"));
        assertEquals("{\"max_completion_tokens\":8192}", entity.getDefaultOptionsJson());
    }

    @Test
    void deadlineFailureRecordsFailedResultInsteadOfLeavingOldSuccess() {
        ModelInstanceEntity entity = savedEntity("timeout-test", ModelInstanceStatus.ACTIVE);
        entity.setLastTestStatus(ModelTestStatus.SUCCESS.name());
        when(mapper.selectById("timeout-test")).thenReturn(entity);
        when(client.chat(any(), any(), any(Duration.class))).thenThrow(new BizException(504, "model call timed out after 30000 ms"));

        ModelInstanceTestResponse response = testService.testSaved("timeout-test");

        assertFalse(response.isSuccess());
        assertEquals(ModelTestStatus.FAILED.name(), entity.getLastTestStatus());
        assertTrue(response.getMessage().contains("timed out"));
        verify(mapper).update(isNull(), any());
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
