package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolControllerTest {

    @Test
    void listsDefinitionsFromMetadataService() {
        ToolDefinitionService definitionService = mock(ToolDefinitionService.class);
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName("demo_tool");
        entity.setDescription("演示工具");
        entity.setSource("code");
        entity.setEnabled(true);
        entity.setAgentVisible(true);
        entity.setLightweightEnabled(false);
        entity.setParametersJson("[{\"name\":\"keyword\",\"type\":\"string\",\"description\":\"关键词\",\"required\":true}]");
        when(definitionService.list()).thenReturn(List.of(entity));

        ToolController controller = new ToolController(definitionService);

        var response = controller.list();
        var body = response.getBody();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(body);
        assertEquals(1, body.size());
        assertEquals("demo_tool", body.get(0).name());
    }

    @Test
    void createsManualToolThroughDefinitionService() {
        ToolDefinitionService definitionService = mock(ToolDefinitionService.class);
        ToolDefinitionEntity saved = new ToolDefinitionEntity();
        saved.setName("manual_tool");
        when(definitionService.create(any())).thenReturn(saved);

        ToolController controller = new ToolController(definitionService);
        ToolController.ToolUpsertRequest request = new ToolController.ToolUpsertRequest(
                "manual_tool",
                "手工工具",
                List.of(new ToolDefinitionParameter("keyword", "string", "关键词", true, "QUERY")),
                "manual",
                null,
                "GET",
                "http://localhost:8088",
                "/api",
                "/search",
                null,
                "String",
                true,
                true,
                false
        );

        var response = controller.create(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(definitionService).create(eq(request.toServiceRequest()));
    }

    @Test
    void returnsNotFoundForMissingToolDetail() {
        ToolDefinitionService definitionService = mock(ToolDefinitionService.class);
        when(definitionService.findByName("missing")).thenReturn(Optional.empty());

        ToolController controller = new ToolController(definitionService);

        var response = controller.get("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
