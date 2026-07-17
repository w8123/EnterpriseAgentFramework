package com.enterprise.ai.model.template;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelTemplateServiceTest {

    private ModelTemplateMapper mapper;
    private ModelTemplateService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ModelTemplateMapper.class);
        service = new ModelTemplateService(mapper, new ObjectMapper());
    }

    @Test
    void listAppliesFilters() {
        ModelTemplateEntity entity = sampleTemplate("tpl-1", "DeepSeek V4", "deepseek", "LLM");
        when(mapper.selectList(any())).thenReturn(List.of(entity));

        List<ModelTemplateResponse> result = service.list("Deep", "deepseek", "LLM", true);

        assertEquals(1, result.size());
        assertEquals("tpl-1", result.get(0).getId());
        assertEquals("https://api.deepseek.com", result.get(0).getConnectionDefaults().get("baseUrl"));
        verify(mapper).selectList(any());
    }

    @Test
    void getReturnsTemplate() {
        when(mapper.selectById("tpl-1")).thenReturn(sampleTemplate("tpl-1", "DeepSeek V4", "deepseek", "LLM"));

        ModelTemplateResponse response = service.get("tpl-1");

        assertEquals("DeepSeek V4", response.getName());
        assertEquals("OPENAI_COMPATIBLE", response.getProtocol());
        assertEquals("/v1/chat/completions", response.getConnectionDefaults().get("chatPath"));
    }

    @Test
    void getMissingThrows404() {
        when(mapper.selectById("missing")).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.get("missing"));
        assertEquals(404, ex.getCode());
        assertTrue(ex.getMessage().contains("not found"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void listPassesWrapperToMapper() {
        when(mapper.selectList(any())).thenReturn(List.of());
        service.list(null, null, null, null);
        ArgumentCaptor<Wrapper<ModelTemplateEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        assertTrue(captor.getValue() != null);
    }

    private ModelTemplateEntity sampleTemplate(String id, String name, String provider, String modelType) {
        ModelTemplateEntity entity = new ModelTemplateEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setProvider(provider);
        entity.setModelType(modelType);
        entity.setModelName("deepseek-chat");
        entity.setProtocol("OPENAI_COMPATIBLE");
        entity.setConnectionDefaultsJson("{\"baseUrl\":\"https://api.deepseek.com\",\"chatPath\":\"/v1/chat/completions\"}");
        entity.setCredentialSchemaJson("[{\"key\":\"apiKey\",\"required\":true}]");
        entity.setDefaultOptionsJson("{\"temperature\":0.7}");
        entity.setParamsSchemaJson("[]");
        entity.setCapabilitiesJson("{\"streaming\":true}");
        entity.setIconKey("deepseek");
        entity.setEnabled(true);
        entity.setSortOrder(10);
        return entity;
    }
}
