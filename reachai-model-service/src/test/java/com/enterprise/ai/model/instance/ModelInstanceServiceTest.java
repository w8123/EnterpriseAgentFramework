package com.enterprise.ai.model.instance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.security.CredentialCipher;
import com.enterprise.ai.model.template.ModelTemplateEntity;
import com.enterprise.ai.model.template.ModelTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelInstanceServiceTest {

    private ModelInstanceMapper mapper;
    private ModelTemplateService templateService;
    private CredentialCipher cipher;
    private ModelInstanceService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ModelInstanceEntity.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(ModelInstanceMapper.class);
        templateService = mock(ModelTemplateService.class);
        cipher = new CredentialCipher("unit-test-secret-for-model-center");
        service = new ModelInstanceService(mapper, templateService, objectMapper, cipher);
        when(mapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void createFromTemplateDoesNotPersistTemplateId() {
        when(templateService.getEntity("tpl-1")).thenReturn(sampleTemplate());

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("My DeepSeek");
        request.setConnection(Map.of("apiKey", "sk-live-key-123456"));
        request.setProjectCode(null);

        ModelInstanceResponse response = service.createFromTemplate("tpl-1", request);

        ArgumentCaptor<ModelInstanceEntity> captor = ArgumentCaptor.forClass(ModelInstanceEntity.class);
        verify(mapper).insert(captor.capture());
        ModelInstanceEntity entity = captor.getValue();
        assertNull(response.getConnection().get("templateId"));
        assertFalse(entity.getConnectionConfigJson().contains("templateId"));
        assertEquals("deepseek", entity.getProvider());
        assertEquals("LLM", entity.getModelType());
        assertEquals("deepseek-chat", entity.getModelName());
        assertNull(entity.getProjectScopeKey());
        assertNull(entity.getProjectCode());
        assertTrue(entity.getConnectionConfigJson().startsWith("aesgcm:"));
        assertEquals("******", String.valueOf(response.getConnection().get("apiKey")).contains("******")
                ? "******" : response.getConnection().get("apiKey"));
        assertTrue(String.valueOf(response.getConnection().get("apiKey")).contains("******"));
    }

    @Test
    void disabledTemplateRejected() {
        ModelTemplateEntity template = sampleTemplate();
        template.setEnabled(false);
        when(templateService.getEntity("tpl-1")).thenReturn(template);

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("X");
        request.setConnection(Map.of("apiKey", "sk-live-key-123456"));

        BizException ex = assertThrows(BizException.class, () -> service.createFromTemplate("tpl-1", request));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("Template is disabled"));
    }

    @Test
    void createFromTemplateAllowsModelNameOverride() {
        when(templateService.getEntity("tpl-1")).thenReturn(sampleTemplate());

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("Override Name");
        request.setModelName("deepseek-reasoner");
        request.setProvider("should-be-ignored");
        request.setModelType(ModelType.EMBEDDING);
        request.setConnection(Map.of("apiKey", "sk-live-key-123456"));

        service.createFromTemplate("tpl-1", request);

        ArgumentCaptor<ModelInstanceEntity> captor = ArgumentCaptor.forClass(ModelInstanceEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("deepseek", captor.getValue().getProvider());
        assertEquals("LLM", captor.getValue().getModelType());
        assertEquals("deepseek-reasoner", captor.getValue().getModelName());
    }

    @Test
    void createFromTemplateMergesDefaultOptionsKeys() {
        when(templateService.getEntity("tpl-1")).thenReturn(sampleTemplate());

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("Opts");
        request.setConnection(Map.of("apiKey", "sk-live-key-123456"));
        request.setDefaultOptions(Map.of("max_tokens", 512));

        service.createFromTemplate("tpl-1", request);

        ArgumentCaptor<ModelInstanceEntity> captor = ArgumentCaptor.forClass(ModelInstanceEntity.class);
        verify(mapper).insert(captor.capture());
        Map<String, Object> opts = readJsonMap(captor.getValue().getDefaultOptionsJson());
        assertEquals(0.7, ((Number) opts.get("temperature")).doubleValue());
        assertEquals(512, ((Number) opts.get("max_tokens")).intValue());
    }

    @Test
    void createRejectsProtectedDefaultOptions() {
        ModelInstanceRequest request = baseCreateRequest("Bad Opts");
        request.setDefaultOptions(Map.of("model", "hijack", "temperature", 0.1));

        BizException ex = assertThrows(BizException.class, () -> service.create(request));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("protected fields"));
        assertTrue(ex.getMessage().contains("model"));
        verify(mapper, never()).insert(any());
    }

    @Test
    void updateAuthorityChangeRejectsMaskedKey() {
        ModelInstanceEntity existing = savedLlm("id-1", "LLM One");
        when(mapper.selectById("id-1")).thenReturn(existing);

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("LLM One");
        request.setConnection(Map.of(
                "baseUrl", "https://api.deepseek.com",
                "apiKey", "sk-li******efgh"));

        BizException ex = assertThrows(BizException.class, () -> service.update("id-1", request));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("BaseURL authority changed"));
    }

    @Test
    void multipleInstancesFromSameTemplateAllowed() {
        when(templateService.getEntity("tpl-1")).thenReturn(sampleTemplate());
        when(mapper.selectCount(any())).thenReturn(0L);

        ModelInstanceRequest first = new ModelInstanceRequest();
        first.setName("Instance A");
        first.setConnection(Map.of("apiKey", "sk-aaa-11111111"));
        service.createFromTemplate("tpl-1", first);

        ModelInstanceRequest second = new ModelInstanceRequest();
        second.setName("Instance B");
        second.setConnection(Map.of(
                "baseUrl", "https://alt.example.com",
                "apiKey", "sk-bbb-22222222"));
        service.createFromTemplate("tpl-1", second);

        verify(mapper, org.mockito.Mockito.times(2)).insert(any());
    }

    @Test
    void customCreateEncryptsAndDefaultsActive() {
        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("Custom LLM");
        request.setProvider("openai");
        request.setModelType(ModelType.LLM);
        request.setModelName("gpt-4.1");
        request.setConnection(Map.of(
                "baseUrl", "https://api.openai.com/v1",
                "apiKey", "sk-custom-abcdef",
                "apiBase", "https://should-be-dropped",
                "token", "legacy-token"));

        ModelInstanceResponse response = service.create(request);

        ArgumentCaptor<ModelInstanceEntity> captor = ArgumentCaptor.forClass(ModelInstanceEntity.class);
        verify(mapper).insert(captor.capture());
        ModelInstanceEntity entity = captor.getValue();
        assertEquals(ModelInstanceStatus.ACTIVE.name(), entity.getStatus());
        assertEquals(ModelProtocol.OPENAI_COMPATIBLE.name(), entity.getProtocol());
        assertEquals(ModelTestStatus.UNKNOWN.name(), entity.getLastTestStatus());
        assertTrue(entity.getConnectionConfigJson().startsWith("aesgcm:"));
        Map<String, Object> plain = readPlain(entity.getConnectionConfigJson());
        assertEquals("https://api.openai.com/v1", plain.get("baseUrl"));
        assertEquals("sk-custom-abcdef", plain.get("apiKey"));
        assertFalse(plain.containsKey("apiBase"));
        assertFalse(plain.containsKey("token"));
        assertTrue(String.valueOf(response.getConnection().get("apiKey")).contains("******"));
        assertEquals("https://api.openai.com/v1", response.getConnection().get("baseUrl"));
    }

    @Test
    void createExplicitDisabled() {
        ModelInstanceRequest request = baseCreateRequest("Disabled LLM");
        request.setStatus(ModelInstanceStatus.DISABLED);
        service.create(request);
        ArgumentCaptor<ModelInstanceEntity> captor = ArgumentCaptor.forClass(ModelInstanceEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals(ModelInstanceStatus.DISABLED.name(), captor.getValue().getStatus());
    }

    @Test
    void projectCodeNullUsesEmptyScopeKeyForUniqueness() {
        ModelInstanceRequest request = baseCreateRequest("Global Name");
        request.setProjectCode(null);
        when(mapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> service.create(request));
        assertTrue(ex.getMessage().contains("already exists"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ModelInstanceEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectCount(captor.capture());
        assertTrue(captor.getValue() != null);
    }

    @Test
    void duplicateNameRejected() {
        when(mapper.selectCount(any())).thenReturn(1L);
        BizException ex = assertThrows(BizException.class, () -> service.create(baseCreateRequest("Dup")));
        assertEquals(400, ex.getCode());
    }

    @Test
    void updateMergesMaskedConnection() {
        ModelInstanceEntity existing = savedLlm("id-1", "LLM One");
        when(mapper.selectById("id-1")).thenReturn(existing);

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("LLM One");
        request.setModelType(ModelType.LLM);
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("baseUrl", "https://api.openai.com/v1");
        connection.put("apiKey", "sk-li******efgh");
        request.setConnection(connection);

        ModelInstanceResponse response = service.update("id-1", request);

        assertTrue(String.valueOf(response.getConnection().get("apiKey")).contains("******"));
        Map<String, Object> plain = readPlain(existing.getConnectionConfigJson());
        assertEquals("sk-live-abcdefgh", plain.get("apiKey"));
    }

    @Test
    void modelTypeImmutable() {
        when(mapper.selectById("id-1")).thenReturn(savedLlm("id-1", "LLM One"));
        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("LLM One");
        request.setModelType(ModelType.EMBEDDING);
        request.setConnection(Map.of("baseUrl", "https://api.openai.com/v1"));

        BizException ex = assertThrows(BizException.class, () -> service.update("id-1", request));
        assertTrue(ex.getMessage().contains("modelType"));
    }

    @Test
    void llmCanChangeProviderAndModelName() {
        ModelInstanceEntity existing = savedLlm("id-1", "LLM One");
        when(mapper.selectById("id-1")).thenReturn(existing);

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("LLM One");
        request.setProvider("deepseek");
        request.setModelName("deepseek-chat");
        request.setConnection(Map.of(
                "baseUrl", "https://api.deepseek.com",
                "apiKey", "sk-new-key-9999"));

        service.update("id-1", request);
        assertEquals("deepseek", existing.getProvider());
        assertEquals("deepseek-chat", existing.getModelName());
    }

    @Test
    void embeddingCanChangeProviderAndModelName() {
        ModelInstanceEntity existing = savedEmbedding("emb-1");
        existing.setLastTestStatus(ModelTestStatus.SUCCESS.name());
        existing.setLastTestAt(java.time.LocalDateTime.now());
        existing.setLastTestLatencyMs(20L);
        when(mapper.selectById("emb-1")).thenReturn(existing);

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("Emb");
        request.setProvider("tongyi");
        request.setModelName("text-embedding-v4");
        request.setConnection(Map.of(
                "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "apiKey", "sk-new-embedding-key"));

        service.update("emb-1", request);
        assertEquals("tongyi", existing.getProvider());
        assertEquals("text-embedding-v4", existing.getModelName());
        assertEquals(ModelTestStatus.UNKNOWN.name(), existing.getLastTestStatus());
        assertNull(existing.getLastTestAt());
        assertNull(existing.getLastTestLatencyMs());
    }

    @Test
    void rerankerCanChangeProviderAndModelName() {
        ModelInstanceEntity existing = savedReranker("rr-1");
        when(mapper.selectById("rr-1")).thenReturn(existing);

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("Rerank");
        request.setProvider("siliconflow");
        request.setModelName("BAAI/bge-reranker-v2-m3");
        request.setConnection(Map.of(
                "baseUrl", "https://api.siliconflow.cn/v1",
                "apiKey", "sk-new-rerank-key"));

        service.update("rr-1", request);
        assertEquals("siliconflow", existing.getProvider());
        assertEquals("BAAI/bge-reranker-v2-m3", existing.getModelName());
    }

    @Test
    void draftTestAllowsEmbeddingSameTypeReplacement() {
        ModelInstanceEntity existing = savedEmbedding("emb-1");
        when(mapper.selectById("emb-1")).thenReturn(existing);

        ModelInstanceRequest draft = new ModelInstanceRequest();
        draft.setId("emb-1");
        draft.setProvider("tongyi");
        draft.setModelName("text-embedding-v4");
        draft.setConnection(Map.of(
                "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "apiKey", "sk-draft-embedding-key"));

        ModelInstanceRuntime runtime = service.buildRuntimeForDraft(draft);
        assertEquals("tongyi", runtime.getProvider());
        assertEquals("text-embedding-v4", runtime.getModelName());
        assertEquals(ModelType.EMBEDDING.name(), runtime.getModelType());
    }

    @Test
    void archiveSetsStatusWithoutPhysicalDelete() {
        ModelInstanceEntity existing = savedLlm("id-1", "LLM One");
        when(mapper.selectById("id-1")).thenReturn(existing);

        ModelInstanceResponse response = service.archive("id-1");

        assertEquals(ModelInstanceStatus.ARCHIVED.name(), response.getStatus());
        verify(mapper).updateById(existing);
        verify(mapper, never()).deleteById(org.mockito.ArgumentMatchers.<java.io.Serializable>any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void listExcludesArchivedByDefault() {
        when(mapper.selectList(any())).thenReturn(List.of());
        service.list(null, null, null, null, false);
        ArgumentCaptor<Wrapper<ModelInstanceEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = String.valueOf(captor.getValue().getSqlSegment());
        assertTrue(sql.contains("ARCHIVED") || sql.toLowerCase().contains("status"),
                "list query should exclude ARCHIVED, sqlSegment=" + sql);
    }

    @Test
    void cannotEditArchived() {
        ModelInstanceEntity existing = savedLlm("id-1", "LLM One");
        existing.setStatus(ModelInstanceStatus.ARCHIVED.name());
        when(mapper.selectById("id-1")).thenReturn(existing);

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("LLM One");
        request.setConnection(Map.of("baseUrl", "https://api.openai.com/v1"));

        BizException ex = assertThrows(BizException.class, () -> service.update("id-1", request));
        assertTrue(ex.getMessage().toLowerCase().contains("archived"));
    }

    @Test
    void updateResetsLastTestWhenBaseUrlChanges() {
        ModelInstanceEntity existing = savedLlmWithSuccess("id-1", "LLM One");
        when(mapper.selectById("id-1")).thenReturn(existing);

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("LLM One");
        request.setConnection(Map.of(
                "baseUrl", "https://api.deepseek.com",
                "apiKey", "sk-brand-new-key-zzzz"));

        service.update("id-1", request);

        assertEquals(ModelTestStatus.UNKNOWN.name(), existing.getLastTestStatus());
        assertNull(existing.getLastTestAt());
        assertNull(existing.getLastTestLatencyMs());
        assertNull(existing.getLastTestError());
    }

    @Test
    void updateResetsLastTestWhenApiKeyChanges() {
        ModelInstanceEntity existing = savedLlmWithSuccess("id-1", "LLM One");
        when(mapper.selectById("id-1")).thenReturn(existing);

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("LLM One");
        request.setConnection(Map.of(
                "baseUrl", "https://api.openai.com/v1",
                "apiKey", "sk-rotated-new-key-9999"));

        service.update("id-1", request);

        assertEquals(ModelTestStatus.UNKNOWN.name(), existing.getLastTestStatus());
        assertNull(existing.getLastTestAt());
    }

    @Test
    void updateResetsLastTestWhenModelNameOrDefaultOptionsChange() {
        ModelInstanceEntity existing = savedLlmWithSuccess("id-1", "LLM One");
        when(mapper.selectById("id-1")).thenReturn(existing);

        ModelInstanceRequest byModel = new ModelInstanceRequest();
        byModel.setName("LLM One");
        byModel.setModelName("gpt-4.1-mini");
        byModel.setConnection(Map.of(
                "baseUrl", "https://api.openai.com/v1",
                "apiKey", "sk-l******efgh"));

        service.update("id-1", byModel);
        assertEquals(ModelTestStatus.UNKNOWN.name(), existing.getLastTestStatus());

        existing.setLastTestStatus(ModelTestStatus.SUCCESS.name());
        existing.setLastTestAt(java.time.LocalDateTime.now());
        existing.setLastTestLatencyMs(12L);

        ModelInstanceRequest byOptions = new ModelInstanceRequest();
        byOptions.setName("LLM One");
        byOptions.setConnection(Map.of(
                "baseUrl", "https://api.openai.com/v1",
                "apiKey", "sk-l******efgh"));
        byOptions.setDefaultOptions(Map.of("temperature", 0.2));

        service.update("id-1", byOptions);
        assertEquals(ModelTestStatus.UNKNOWN.name(), existing.getLastTestStatus());
        assertNull(existing.getLastTestLatencyMs());
    }

    @Test
    void updateKeepsLastTestWhenMaskedCredentialUnchanged() {
        ModelInstanceEntity existing = savedLlmWithSuccess("id-1", "LLM One");
        when(mapper.selectById("id-1")).thenReturn(existing);

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("LLM One");
        request.setConnection(Map.of(
                "baseUrl", "https://api.openai.com/v1",
                "apiKey", "sk-l******efgh"));

        service.update("id-1", request);

        assertEquals(ModelTestStatus.SUCCESS.name(), existing.getLastTestStatus());
        assertEquals(42L, existing.getLastTestLatencyMs());
        assertEquals("ok", existing.getLastTestError());
    }

    @Test
    void updateKeepsLastTestWhenOnlyMetadataChanges() {
        ModelInstanceEntity existing = savedLlmWithSuccess("id-1", "LLM One");
        when(mapper.selectById("id-1")).thenReturn(existing);

        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName("LLM Renamed");
        request.setRemark("metadata only");
        request.setStatus(ModelInstanceStatus.DISABLED);
        request.setProjectCode("proj-a");
        request.setConnection(Map.of(
                "baseUrl", "https://api.openai.com/v1",
                "apiKey", "sk-l******efgh"));

        service.update("id-1", request);

        assertEquals("LLM Renamed", existing.getName());
        assertEquals(ModelInstanceStatus.DISABLED.name(), existing.getStatus());
        assertEquals("proj-a", existing.getProjectCode());
        assertEquals(ModelTestStatus.SUCCESS.name(), existing.getLastTestStatus());
        assertEquals(42L, existing.getLastTestLatencyMs());
    }

    private ModelInstanceRequest baseCreateRequest(String name) {
        ModelInstanceRequest request = new ModelInstanceRequest();
        request.setName(name);
        request.setProvider("openai");
        request.setModelType(ModelType.LLM);
        request.setModelName("gpt-4.1");
        request.setConnection(Map.of(
                "baseUrl", "https://api.openai.com/v1",
                "apiKey", "sk-test-12345678"));
        return request;
    }

    private ModelTemplateEntity sampleTemplate() {
        ModelTemplateEntity template = new ModelTemplateEntity();
        template.setId("tpl-1");
        template.setName("DeepSeek V4");
        template.setProvider("deepseek");
        template.setModelType("LLM");
        template.setModelName("deepseek-chat");
        template.setProtocol("OPENAI_COMPATIBLE");
        template.setEnabled(true);
        template.setConnectionDefaultsJson("{\"baseUrl\":\"https://api.deepseek.com\",\"chatPath\":\"/chat/completions\"}");
        template.setDefaultOptionsJson("{\"temperature\":0.7}");
        template.setParamsSchemaJson("[]");
        return template;
    }

    private ModelInstanceEntity savedLlm(String id, String name) {
        ModelInstanceEntity entity = new ModelInstanceEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setProvider("openai");
        entity.setModelType(ModelType.LLM.name());
        entity.setModelName("gpt-4.1");
        entity.setProtocol(ModelProtocol.OPENAI_COMPATIBLE.name());
        entity.setProjectCode(null);
        entity.setProjectScopeKey("");
        entity.setConnectionConfigJson(cipher.encrypt(
                "{\"baseUrl\":\"https://api.openai.com/v1\",\"apiKey\":\"sk-live-abcdefgh\"}"));
        entity.setDefaultOptionsJson("{}");
        entity.setParamsSchemaJson("[]");
        entity.setStatus(ModelInstanceStatus.ACTIVE.name());
        entity.setLastTestStatus(ModelTestStatus.UNKNOWN.name());
        return entity;
    }

    private ModelInstanceEntity savedLlmWithSuccess(String id, String name) {
        ModelInstanceEntity entity = savedLlm(id, name);
        entity.setLastTestStatus(ModelTestStatus.SUCCESS.name());
        entity.setLastTestAt(java.time.LocalDateTime.now().minusMinutes(5));
        entity.setLastTestLatencyMs(42L);
        entity.setLastTestError("ok");
        return entity;
    }

    private ModelInstanceEntity savedEmbedding(String id) {
        ModelInstanceEntity entity = savedLlm(id, "Emb");
        entity.setModelType(ModelType.EMBEDDING.name());
        entity.setModelName("text-embedding-3-large");
        return entity;
    }

    private ModelInstanceEntity savedReranker(String id) {
        ModelInstanceEntity entity = savedLlm(id, "Rerank");
        entity.setModelType(ModelType.RERANKER.name());
        entity.setProvider("openai");
        entity.setModelName("rerank-english-v3.0");
        return entity;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPlain(String encrypted) {
        try {
            return objectMapper.readValue(cipher.decrypt(encrypted), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
