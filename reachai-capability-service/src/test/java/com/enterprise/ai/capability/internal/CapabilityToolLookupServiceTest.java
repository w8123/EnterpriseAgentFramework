package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityToolLookupServiceTest {

    private final ToolDefinitionMapper toolDefinitionMapper = mock(ToolDefinitionMapper.class);
    private final CapabilityToolLookupService service = new CapabilityToolLookupService(toolDefinitionMapper);

    @Test
    void returnsToolDefinitionByQualifiedName() {
        ToolDefinitionEntity entity = newToolDefinition();
        when(toolDefinitionMapper.selectOne(any())).thenReturn(entity);

        Map<String, Object> tool = service.getToolDefinition("orders:createOrder");

        assertEquals(12L, tool.get("id"));
        assertEquals("createOrder", tool.get("name"));
        assertEquals("TOOL", tool.get("kind"));
        assertEquals("orders:createOrder", tool.get("qualifiedName"));
        assertEquals("orders", tool.get("projectCode"));
        assertEquals(Boolean.TRUE, tool.get("enabled"));
        assertEquals("2026-06-29T10:00", tool.get("createTime"));
    }

    @Test
    void rejectsMissingToolDefinition() {
        when(toolDefinitionMapper.selectOne(any())).thenReturn(null);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.getToolDefinition("missing.tool")
        );

        assertEquals("Tool definition not found: missing.tool", ex.getMessage());
    }

    private ToolDefinitionEntity newToolDefinition() {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setId(12L);
        entity.setName("createOrder");
        entity.setKind("TOOL");
        entity.setDescription("Create order");
        entity.setAiDescription("Creates an order");
        entity.setCapabilityMetadataJson("{}");
        entity.setParametersJson("[]");
        entity.setSpecJson(null);
        entity.setSource("sdk");
        entity.setSourceLocation("sdk:orders:createOrder");
        entity.setHttpMethod("POST");
        entity.setBaseUrl("http://orders.local");
        entity.setContextPath("/orders");
        entity.setEndpointPath("/create");
        entity.setRequestBodyType("JSON");
        entity.setResponseType("JSON");
        entity.setProjectId(7L);
        entity.setProjectCode("orders");
        entity.setVisibility("PROJECT");
        entity.setQualifiedName("orders:createOrder");
        entity.setModuleId(3L);
        entity.setEnabled(true);
        entity.setAgentVisible(true);
        entity.setSideEffect("WRITE");
        entity.setSkillKind(null);
        entity.setDraft(false);
        entity.setLightweightEnabled(true);
        entity.setCreateTime(LocalDateTime.of(2026, 6, 29, 10, 0));
        entity.setUpdateTime(LocalDateTime.of(2026, 6, 29, 10, 5));
        return entity;
    }
}
