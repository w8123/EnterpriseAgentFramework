package com.enterprise.ai.agent.scan;

import com.enterprise.ai.agent.client.ScannerServiceClient;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScanProjectServiceTest {

    @Test
    void scansOpenApiProjectAndCreatesDisabledProjectTools() {
        try {
            Path tempDir = Files.createTempDirectory("scan-project-openapi");
            Files.writeString(tempDir.resolve("openapi.yaml"), "openapi: 3.0.0\npaths: {}\n");

        ScanProjectMapper projectMapper = mock(ScanProjectMapper.class);
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        ScannerServiceClient scannerServiceClient = mock(ScannerServiceClient.class);
        when(toolDefinitionService.listByProjectId(1L)).thenReturn(List.of());

        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(1L);
        project.setName("legacy-crm");
        project.setBaseUrl("http://localhost:9001");
        project.setContextPath("/api");
        project.setScanPath(tempDir.toString());
        project.setScanType("openapi");
        project.setSpecFile("openapi.yaml");
        when(projectMapper.selectById(1L)).thenReturn(project);

        ScannerServiceClient.ScanManifestResult manifest = new ScannerServiceClient.ScanManifestResult(
                200,
                "success",
                new ScannerServiceClient.ManifestData(
                        new ScannerServiceClient.ProjectData("legacy-crm", "http://localhost:9001", "/api"),
                        List.of(new ScannerServiceClient.ToolData(
                                "query_customer",
                                "查询客户",
                                "GET",
                                "/customer/search",
                                "GET /api/customer/search",
                                List.of(new ScannerServiceClient.ToolParameterData("keyword", "string", "关键词", true, "QUERY")),
                                null,
                                "CustomerList",
                                new ScannerServiceClient.ToolSourceData("openapi", "openapi.yaml#/paths/~1customer~1search/get")
                        ))
                )
        );
        when(scannerServiceClient.scanOpenApi(any(ScannerServiceClient.ScanRequest.class))).thenReturn(manifest);

        ScanProjectService service = new ScanProjectService(projectMapper, toolDefinitionService, scannerServiceClient);

        ScanProjectService.ScanResult result = service.scan(1L);

        assertEquals(1, result.toolCount());
        assertEquals(List.of("legacy_crm__query_customer"), result.toolNames());
        verify(toolDefinitionService).create(argThat(request ->
                request.projectId().equals(1L)
                        && request.name().equals("legacy_crm__query_customer")
                        && !request.enabled()
                        && !request.agentVisible()
                        && request.parameters().size() == 1
        ));
        ArgumentCaptor<ScanProjectEntity> captor = ArgumentCaptor.forClass(ScanProjectEntity.class);
        verify(projectMapper, org.mockito.Mockito.times(2)).updateById(captor.capture());
        ScanProjectEntity lastUpdated = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(1L, lastUpdated.getId());
        assertEquals("scanned", lastUpdated.getStatus());
        assertEquals(1, lastUpdated.getToolCount());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void rescansControllerProjectAfterDeletingExistingTools() {
        ScanProjectMapper projectMapper = mock(ScanProjectMapper.class);
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        ScannerServiceClient scannerServiceClient = mock(ScannerServiceClient.class);

        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(2L);
        project.setName("legacy-order");
        project.setBaseUrl("http://localhost:9002");
        project.setContextPath("/api");
        project.setScanPath("D:/legacy-order/src/main/java");
        project.setScanType("controller");
        when(projectMapper.selectById(2L)).thenReturn(project);

        ScannerServiceClient.ScanManifestResult manifest = new ScannerServiceClient.ScanManifestResult(
                200,
                "success",
                new ScannerServiceClient.ManifestData(
                        new ScannerServiceClient.ProjectData("legacy-order", "http://localhost:9002", "/api"),
                        List.of(new ScannerServiceClient.ToolData(
                                "create_order",
                                "创建订单",
                                "POST",
                                "/order/create",
                                "POST /api/order/create",
                                List.of(new ScannerServiceClient.ToolParameterData("body_json", "json", "请求体", true, "BODY")),
                                "CreateOrderRequest",
                                "CreateOrderResponse",
                                new ScannerServiceClient.ToolSourceData("controller", "OrderController.java#OrderController#createOrder")
                        ))
                )
        );
        when(scannerServiceClient.scanController(any(ScannerServiceClient.ScanRequest.class))).thenReturn(manifest);

        ScanProjectService service = new ScanProjectService(projectMapper, toolDefinitionService, scannerServiceClient);

        ScanProjectService.ScanResult result = service.rescan(2L);

        assertTrue(result.toolNames().contains("legacy_order__create_order"));
        InOrder inOrder = inOrder(toolDefinitionService, scannerServiceClient);
        inOrder.verify(toolDefinitionService).deleteByProjectId(2L);
        inOrder.verify(scannerServiceClient).scanController(any(ScannerServiceClient.ScanRequest.class));
    }

    @Test
    void rejectsDuplicateScanResultsUntilRescanIsUsed() {
        ScanProjectMapper projectMapper = mock(ScanProjectMapper.class);
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        ScannerServiceClient scannerServiceClient = mock(ScannerServiceClient.class);

        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(3L);
        project.setName("legacy-crm");
        when(projectMapper.selectById(3L)).thenReturn(project);

        ToolDefinitionEntity existingTool = new ToolDefinitionEntity();
        existingTool.setId(99L);
        existingTool.setProjectId(3L);
        existingTool.setName("legacy_crm__query_customer");
        when(toolDefinitionService.listByProjectId(3L)).thenReturn(List.of(existingTool));

        ScanProjectService service = new ScanProjectService(projectMapper, toolDefinitionService, scannerServiceClient);

        try {
            service.scan(3L);
        } catch (IllegalArgumentException ex) {
            assertEquals("项目已有扫描结果，请使用重新扫描", ex.getMessage());
            return;
        }
        throw new AssertionError("expected duplicate scan to be rejected");
    }

    @Test
    void scanSendsNonBlankProjectNameWhenDisplayNameIsNonAsciiOnly() throws Exception {
        Path tempDir = Files.createTempDirectory("scan-project-cn-name");
        Files.writeString(tempDir.resolve("openapi.yaml"), "openapi: 3.0.0\npaths: {}\n");

        ScanProjectMapper projectMapper = mock(ScanProjectMapper.class);
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        ScannerServiceClient scannerServiceClient = mock(ScannerServiceClient.class);
        when(toolDefinitionService.listByProjectId(10L)).thenReturn(List.of());

        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(10L);
        project.setName("订单系统");
        project.setBaseUrl("http://localhost:9001");
        project.setContextPath("/api");
        project.setScanPath(tempDir.toString());
        project.setScanType("openapi");
        project.setSpecFile("openapi.yaml");
        when(projectMapper.selectById(10L)).thenReturn(project);

        ScannerServiceClient.ScanManifestResult manifest = new ScannerServiceClient.ScanManifestResult(
                200,
                "success",
                new ScannerServiceClient.ManifestData(
                        new ScannerServiceClient.ProjectData("订单系统", "http://localhost:9001", "/api"),
                        List.of()
                )
        );
        when(scannerServiceClient.scanOpenApi(any(ScannerServiceClient.ScanRequest.class))).thenReturn(manifest);

        ScanProjectService service = new ScanProjectService(projectMapper, toolDefinitionService, scannerServiceClient);
        service.scan(10L);

        ArgumentCaptor<ScannerServiceClient.ScanRequest> captor = ArgumentCaptor.forClass(ScannerServiceClient.ScanRequest.class);
        verify(scannerServiceClient).scanOpenApi(captor.capture());
        assertEquals("订单系统", captor.getValue().getProjectName());
    }
}
