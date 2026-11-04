package com.enterprise.ai.model.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.common.exception.GlobalExceptionHandler;
import com.enterprise.ai.model.account.ModelProviderAccountMapper;
import com.enterprise.ai.model.instance.*;
import com.enterprise.ai.model.runtime.OpenAiCompatibleRuntimeClient;
import com.enterprise.ai.model.security.CredentialCipher;
import com.enterprise.ai.model.service.ModelRoutingService;
import com.enterprise.ai.model.template.ModelTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Regression for saved-key disclosure via malformed authentication headers; no external I/O. */
class ModelCredentialPrivacyTest {
    private static final String SECRET = "sk-SYNTHETIC_SAVED_PRIVATE_KEY_12345678";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final CredentialCipher cipher = new CredentialCipher("isolated-privacy-test-secret");
    private ModelInstanceEntity entity;
    private ModelInstanceMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ModelInstanceEntity.class);
        entity = new ModelInstanceEntity();
        entity.setId("privacy-model");
        entity.setName("Privacy model");
        entity.setProvider("custom");
        entity.setModelType("LLM");
        entity.setModelName("fixture");
        entity.setProtocol("OPENAI_COMPATIBLE");
        entity.setStatus("ACTIVE");
        entity.setDefaultOptionsJson("{}");
        entity.setParamsSchemaJson("[]");
        saveConnection(Map.of("baseUrl", "https://fixture.invalid/v1", "apiKey", SECRET));
        mapper = mock(ModelInstanceMapper.class);
        when(mapper.selectById("privacy-model")).thenReturn(entity);
        when(mapper.selectCount(any())).thenReturn(0L);
        ModelInstanceRuntimeCache cache = new ModelInstanceRuntimeCache(5000L);
        ModelInstanceService service = new ModelInstanceService(mapper, mock(ModelTemplateService.class), json, cipher,
                cache, mock(ModelProviderAccountMapper.class));
        mvc = MockMvcBuilders.standaloneSetup(
                new ModelInstanceController(service, mock(ModelInstanceTestService.class), mock(ModelDiscoveryService.class)),
                new ModelController(new ModelRoutingService(service, cache, new OpenAiCompatibleRuntimeClient(json))))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void illegalAuthenticationEditIsRejectedWithoutLeakingOrReplacingSavedKey() throws Exception {
        String mutation = json.writeValueAsString(Map.of("name", entity.getName(),
                "connection", Map.of("authPrefix", "Bearer \r\n")));
        String body = mvc.perform(put("/model/instances/privacy-model").contentType(MediaType.APPLICATION_JSON)
                .content(mutation)).andReturn().getResponse().getContentAsString();
        assertEquals(400, json.readTree(body).path("code").asInt());
        assertFalse(body.contains(SECRET));
        verify(mapper, never()).updateById(any(ModelInstanceEntity.class));
        assertTrue(cipher.decrypt(entity.getConnectionConfigJson()).contains(SECRET));
    }

    @Test
    void previouslySavedInvalidHeaderCannotExposeKeyInChatError() throws Exception {
        saveConnection(Map.of("baseUrl", "https://fixture.invalid/v1", "apiKey", SECRET, "authPrefix", "Bearer \r\n"));
        String request = "{\"modelInstanceId\":\"privacy-model\",\"messages\":[{\"role\":\"user\",\"content\":\"fixture\"}]}";
        String body = mvc.perform(post("/model/chat").contentType(MediaType.APPLICATION_JSON).content(request))
                .andReturn().getResponse().getContentAsString();
        assertEquals(400, json.readTree(body).path("code").asInt());
        assertFalse(body.contains(SECRET));
        assertFalse(body.contains("12345678"));
        assertFalse(body.contains("invalid header value"));
    }

    @Test
    void editingWithoutKeyPreservesItAndReturnsOnlyConfigurationStatus() throws Exception {
        String body = mvc.perform(put("/model/instances/privacy-model").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed model\"}")).andReturn().getResponse().getContentAsString();
        assertEquals(200, json.readTree(body).path("code").asInt());
        assertTrue(json.readTree(body).at("/data/apiKeyConfigured").asBoolean());
        assertFalse(json.readTree(body).at("/data/connection").has("apiKey"));
        assertFalse(body.contains("12345678"));
        assertTrue(cipher.decrypt(entity.getConnectionConfigJson()).contains(SECRET));
        String read = mvc.perform(get("/model/instances/privacy-model")).andReturn().getResponse().getContentAsString();
        assertFalse(json.readTree(read).at("/data/connection").has("apiKey"));
        assertTrue(json.readTree(read).at("/data/apiKeyConfigured").asBoolean());
    }

    private void saveConnection(Map<String, Object> connection) throws Exception {
        entity.setConnectionConfigJson(cipher.encrypt(json.writeValueAsString(connection)));
    }
}
