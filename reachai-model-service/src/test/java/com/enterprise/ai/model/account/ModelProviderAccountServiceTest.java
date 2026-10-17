package com.enterprise.ai.model.account;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ModelInstanceEntity;
import com.enterprise.ai.model.instance.ModelInstanceMapper;
import com.enterprise.ai.model.instance.ModelInstanceResponse;
import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.instance.ModelInstanceRuntimeCache;
import com.enterprise.ai.model.instance.ModelInstanceService;
import com.enterprise.ai.model.instance.ModelType;
import com.enterprise.ai.model.runtime.OpenAiCompatibleRuntimeClient;
import com.enterprise.ai.model.runtime.UpstreamModel;
import com.enterprise.ai.model.security.CredentialCipher;
import com.enterprise.ai.model.template.ModelTemplateEntity;
import com.enterprise.ai.model.template.ModelTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelProviderAccountServiceTest {

    private final CredentialCipher cipher = new CredentialCipher("unit-test-secret-for-model-center");
    private ModelProviderAccountMapper mapper;
    private ModelInstanceMapper instanceMapper;
    private ModelInstanceService instanceService;
    private ModelTemplateService templateService;
    private OpenAiCompatibleRuntimeClient client;
    private ModelInstanceRuntimeCache cache;
    private ModelProviderAccountService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ModelInstanceEntity.class);
        TableInfoHelper.initTableInfo(assistant, ModelProviderAccountEntity.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(ModelProviderAccountMapper.class);
        instanceMapper = mock(ModelInstanceMapper.class);
        instanceService = mock(ModelInstanceService.class);
        templateService = mock(ModelTemplateService.class);
        client = mock(OpenAiCompatibleRuntimeClient.class);
        cache = mock(ModelInstanceRuntimeCache.class);
        service = new ModelProviderAccountService(mapper, instanceMapper, instanceService, templateService,
                client, cipher, cache, new ObjectMapper());
        when(mapper.selectById("acct-1")).thenReturn(account());
        when(instanceMapper.selectList(any())).thenReturn(List.of());
        when(instanceMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void createStoresOnlyAddressAndCredentialEncrypted() throws Exception {
        ModelProviderAccountRequest request = new ModelProviderAccountRequest();
        request.setName("百炼主账号");
        request.setProvider("tongyi");
        request.setConnection(Map.of(
                "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "apiKey", "sk-new-12345678",
                "chatPath", "/ignored"));

        ModelProviderAccountResponse response = service.create(request);

        ArgumentCaptor<ModelProviderAccountEntity> saved = ArgumentCaptor.forClass(ModelProviderAccountEntity.class);
        verify(mapper).insert(saved.capture());
        assertFalse(saved.getValue().getConnectionConfigJson().contains("sk-new"));
        Map<?, ?> stored = new ObjectMapper().readValue(cipher.decrypt(saved.getValue().getConnectionConfigJson()), Map.class);
        assertEquals(Map.of("baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1", "apiKey", "sk-new-12345678"), stored);
        assertTrue(response.isApiKeyConfigured());
        assertFalse(String.valueOf(response.getConnection().get("apiKey")).contains("12345678"));
    }

    @Test
    void discoverMarksRecommendedAndAlreadyConnectedModels() {
        when(templateService.enabledEntitiesForProvider("tongyi")).thenReturn(List.of(template("qwen-plus", "LLM")));
        when(instanceMapper.selectList(any())).thenReturn(List.of(instance("qwen3-max", "LLM")));
        when(client.listModels(any(ModelInstanceRuntime.class))).thenReturn(List.of(
                new UpstreamModel("qwen3-max", null, 2L, "LLM"), new UpstreamModel("qwen-plus", null, 1L, "LLM")));

        ProviderModelsResponse response = service.discoverSaved("acct-1");

        assertTrue(response.success());
        assertEquals(List.of("qwen-plus"), response.recommendedModelNames());
        assertEquals("qwen3-max", response.connected().get(0).modelName());
        ArgumentCaptor<ModelInstanceRuntime> runtime = ArgumentCaptor.forClass(ModelInstanceRuntime.class);
        verify(client).listModels(runtime.capture());
        assertEquals("sk-account-12345678", runtime.getValue().getConnectionConfig().get("apiKey"));
    }

    @Test
    void batchCreateSkipsConnectedModelsAndUsesTemplateDefaults() {
        when(templateService.enabledEntitiesForProvider("tongyi")).thenReturn(List.of(template("text-embedding-v4", "EMBEDDING")));
        when(instanceMapper.selectList(any())).thenReturn(List.of(instance("qwen3-max", "LLM")));
        ModelInstanceResponse created = ModelInstanceResponse.builder().id("new-1").name("text-embedding-v4").build();
        when(instanceService.createForAccount(any(), any(), any(), any(), anyMap(), anyMap())).thenReturn(created);

        ProviderAccountInstancesResponse response = service.createInstances("acct-1", new ProviderAccountInstancesRequest(List.of(
                new ProviderAccountInstancesRequest.Item("qwen3-max", "LLM", null),
                new ProviderAccountInstancesRequest.Item("text-embedding-v4", "EMBEDDING", null),
                new ProviderAccountInstancesRequest.Item("whatever", "IMAGE", null))));

        assertEquals(1, response.created().size());
        assertEquals(List.of("qwen3-max", "whatever"), response.skipped().stream().map(ProviderAccountInstancesResponse.Skipped::modelName).toList());
        verify(instanceService).createForAccount(any(), eq("text-embedding-v4"), eq(ModelType.EMBEDDING), eq("text-embedding-v4"),
                eq(Map.of("embeddingPath", "/embeddings")), eq(Map.of("dimensions", 1024)));
    }

    @Test
    void deleteRefusedWhileModelsStillUseTheAccount() {
        when(instanceMapper.selectCount(any())).thenReturn(2L);

        assertThrows(BizException.class, () -> service.delete("acct-1"));
        verify(mapper, never()).deleteById(any(String.class));
    }

    @Test
    void updatingAccountInvalidatesCachedRuntimes() {
        ModelProviderAccountRequest request = new ModelProviderAccountRequest();
        request.setConnection(Map.of("baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1", "apiKey", "sk-rotated-87654321"));

        service.update("acct-1", request);

        verify(cache).invalidateAll();
    }

    private ModelProviderAccountEntity account() {
        ModelProviderAccountEntity account = new ModelProviderAccountEntity();
        account.setId("acct-1");
        account.setName("百炼主账号");
        account.setProvider("tongyi");
        account.setConnectionConfigJson(cipher.encrypt(
                "{\"baseUrl\":\"https://dashscope.aliyuncs.com/compatible-mode/v1\",\"apiKey\":\"sk-account-12345678\"}"));
        return account;
    }

    private ModelTemplateEntity template(String modelName, String type) {
        ModelTemplateEntity template = new ModelTemplateEntity();
        template.setProvider("tongyi");
        template.setModelName(modelName);
        template.setModelType(type);
        template.setConnectionDefaultsJson("{\"baseUrl\":\"https://dashscope.aliyuncs.com/compatible-mode/v1\",\"embeddingPath\":\"/embeddings\"}");
        template.setDefaultOptionsJson("{\"dimensions\":1024}");
        return template;
    }

    private ModelInstanceEntity instance(String modelName, String type) {
        ModelInstanceEntity instance = new ModelInstanceEntity();
        instance.setId("i-" + modelName);
        instance.setName(modelName);
        instance.setModelName(modelName);
        instance.setModelType(type);
        return instance;
    }
}
