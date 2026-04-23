package com.enterprise.ai.agent.tools.dynamic;

import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicHttpAiToolDescriptionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preferAiDescriptionWhenPresent() {
        ToolDefinitionEntity entity = baseEntity();
        entity.setDescription("结构化描述");
        entity.setAiDescription("## 一句话语义\n创建订单并锁定库存。");

        DynamicHttpAiTool tool = new DynamicHttpAiTool(entity, objectMapper);

        assertEquals("## 一句话语义\n创建订单并锁定库存。", tool.description());
    }

    @Test
    void fallbackToDescriptionWhenAiDescriptionBlank() {
        ToolDefinitionEntity entity = baseEntity();
        entity.setDescription("结构化描述");
        entity.setAiDescription("   ");

        DynamicHttpAiTool tool = new DynamicHttpAiTool(entity, objectMapper);

        assertEquals("结构化描述", tool.description());
    }

    private ToolDefinitionEntity baseEntity() {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName("demo_tool");
        entity.setHttpMethod("GET");
        entity.setBaseUrl("http://localhost:9001");
        entity.setContextPath("/api");
        entity.setEndpointPath("/demo");
        entity.setParametersJson("[]");
        return entity;
    }
}
