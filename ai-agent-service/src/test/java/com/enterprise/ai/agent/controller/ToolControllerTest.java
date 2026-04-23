package com.enterprise.ai.agent.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolControllerTest {

    @Test
    void noLongerExposesManifestImportEndpoint() {
        boolean hasImportEndpoint = Arrays.stream(ToolController.class.getDeclaredMethods())
                .anyMatch(method -> "importManifest".equals(method.getName()));

        assertFalse(hasImportEndpoint);
    }

    @Test
    void listsDefinitionsFromMetadataService() {
        ToolDefinitionService definitionService = mock(ToolDefinitionService.class);
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        when(scanProjectService.getProjectNameOrNull(9L)).thenReturn("test_scan_project");
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName("demo_tool");
        entity.setDescription("演示工具");
        entity.setSource("code");
        entity.setProjectId(9L);
        entity.setEnabled(true);
        entity.setAgentVisible(true);
        entity.setLightweightEnabled(false);
        entity.setParametersJson("[{\"name\":\"keyword\",\"type\":\"string\",\"description\":\"关键词\",\"required\":true}]");
        Page<ToolDefinitionEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(entity));
        page.setTotal(1L);
        when(definitionService.page(1, 20, null, null, null, null)).thenReturn(page);

        ToolController controller = new ToolController(definitionService, scanProjectService);

        var response = controller.list(1, 20, null, null, null, null);
        var body = response.getBody();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(body);
        assertEquals(1, body.records().size());
        assertEquals(1L, body.total());
        assertEquals("demo_tool", body.records().get(0).name());
        assertEquals(9L, body.records().get(0).projectId());
        assertEquals("test_scan_project", body.records().get(0).sourceProjectName());
    }

    @Test
    void createsManualToolThroughDefinitionService() {
        ToolDefinitionService definitionService = mock(ToolDefinitionService.class);
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        ToolDefinitionEntity saved = new ToolDefinitionEntity();
        saved.setName("manual_tool");
        when(definitionService.create(any())).thenReturn(saved);

        ToolController controller = new ToolController(definitionService, scanProjectService);
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
                null,
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
        ToolController controller = new ToolController(definitionService, mock(ScanProjectService.class));

        var response = controller.get("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void includesProjectIdWhenCreatingTool() {
        ToolDefinitionService definitionService = mock(ToolDefinitionService.class);
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        when(scanProjectService.getProjectNameOrNull(7L)).thenReturn("legacy_crm");
        ToolDefinitionEntity saved = new ToolDefinitionEntity();
        saved.setName("legacy_crm__query_customer");
        saved.setProjectId(7L);
        when(definitionService.create(any())).thenReturn(saved);

        ToolController controller = new ToolController(definitionService, scanProjectService);
        ToolController.ToolUpsertRequest request = new ToolController.ToolUpsertRequest(
                "legacy_crm__query_customer",
                "查询客户",
                List.of(new ToolDefinitionParameter("keyword", "string", "关键词", true, "QUERY")),
                "scanner",
                "CustomerController#queryCustomer",
                "GET",
                "http://localhost:9001",
                "/api",
                "/customer/search",
                null,
                "CustomerList",
                7L,
                false,
                false,
                false
        );

        var response = controller.create(request);
        var body = response.getBody();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(body);
        assertEquals(7L, body.projectId());
        assertEquals("legacy_crm", body.sourceProjectName());
        verify(definitionService).create(eq(request.toServiceRequest()));
    }
}
