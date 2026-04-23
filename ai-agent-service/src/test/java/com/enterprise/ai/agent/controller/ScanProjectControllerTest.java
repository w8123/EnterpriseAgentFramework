package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScanProjectControllerTest {

    @Test
    void scansProjectThroughService() {
        ScanProjectService service = mock(ScanProjectService.class);
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        when(service.scan(1L)).thenReturn(new ScanProjectService.ScanResult(1L, "legacy-crm", 1, List.of("legacy_crm__query_customer")));

        ScanProjectController controller = new ScanProjectController(service, toolDefinitionService);

        var response = controller.scan(1L);
        var body = (ScanProjectController.ScanResultDTO) response.getBody();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(body);
        assertEquals(1, body.toolCount());
        verify(service).scan(1L);
    }

    @Test
    void returnsNotFoundWhenScanningMissingProject() {
        ScanProjectService service = mock(ScanProjectService.class);
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        when(service.scan(9L)).thenThrow(new IllegalArgumentException("扫描项目不存在: 9"));

        ScanProjectController controller = new ScanProjectController(service, toolDefinitionService);

        var response = controller.scan(9L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void marksProjectFailedWhenScanThrowsBusinessError() {
        ScanProjectService service = mock(ScanProjectService.class);
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        when(service.scan(5L)).thenThrow(new IllegalArgumentException("未找到 OpenAPI 规范文件"));

        ScanProjectController controller = new ScanProjectController(service, toolDefinitionService);

        var response = controller.scan(5L);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(service).markFailed(5L, "未找到 OpenAPI 规范文件");
    }

    @Test
    void listsProjectTools() {
        ScanProjectService service = mock(ScanProjectService.class);
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setName("legacy_crm__query_customer");
        tool.setDescription("查询客户");
        tool.setProjectId(7L);
        tool.setSource("scanner");
        tool.setParametersJson("[{\"name\":\"keyword\",\"type\":\"string\",\"description\":\"关键词\",\"required\":true,\"location\":\"QUERY\"}]");
        tool.setEnabled(false);
        tool.setAgentVisible(false);
        tool.setLightweightEnabled(false);
        when(service.listTools(7L)).thenReturn(List.of(tool));
        when(toolDefinitionService.parseParameters(tool.getParametersJson())).thenReturn(List.of(
                new com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter("keyword", "string", "关键词", true, "QUERY")
        ));

        ScanProjectController controller = new ScanProjectController(service, toolDefinitionService);

        var response = controller.listTools(7L);
        var body = response.getBody();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(body);
        assertEquals(1, body.size());
        assertEquals(7L, body.get(0).projectId());
        assertEquals(1, body.get(0).parameters().size());
        verify(service).listTools(7L);
    }

    @Test
    void createsScanProject() {
        ScanProjectService service = mock(ScanProjectService.class);
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        ScanProjectEntity entity = new ScanProjectEntity();
        entity.setId(3L);
        entity.setName("legacy-crm");
        entity.setScanType("openapi");
        entity.setStatus("created");
        when(service.create(new ScanProjectService.ScanProjectUpsertRequest(
                "legacy-crm",
                "http://localhost:9001",
                "/api",
                "D:/legacy-crm",
                "openapi",
                "openapi.yaml"
        ))).thenReturn(entity);

        ScanProjectController controller = new ScanProjectController(service, toolDefinitionService);
        ScanProjectController.ScanProjectUpsertRequest request = new ScanProjectController.ScanProjectUpsertRequest(
                "legacy-crm",
                "http://localhost:9001",
                "/api",
                "D:/legacy-crm",
                "openapi",
                "openapi.yaml"
        );

        var response = controller.create(request);
        var body = response.getBody();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(body);
        assertEquals("legacy-crm", body.name());
    }
}
