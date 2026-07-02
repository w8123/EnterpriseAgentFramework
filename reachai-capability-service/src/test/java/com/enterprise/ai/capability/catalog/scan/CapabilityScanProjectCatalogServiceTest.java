package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectBlockers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocMapper;
import com.enterprise.ai.agent.registry.RegistryCredentialMapper;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.capability.internal.CapabilityToolExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityScanProjectCatalogServiceTest {

    private final ScanProjectMapper scanProjectMapper = mock(ScanProjectMapper.class);
    private final ScanProjectToolMapper scanProjectToolMapper = mock(ScanProjectToolMapper.class);
    private final ToolDefinitionMapper toolDefinitionMapper = mock(ToolDefinitionMapper.class);
    private final ScanModuleMapper scanModuleMapper = mock(ScanModuleMapper.class);
    private final SemanticDocMapper semanticDocMapper = mock(SemanticDocMapper.class);
    private final RegistryCredentialMapper registryCredentialMapper = mock(RegistryCredentialMapper.class);
    private final RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
    private final CapabilityScanProjectBlockerService scanProjectBlockerService =
            mock(CapabilityScanProjectBlockerService.class);
    private final CapabilityToolExecutionService toolExecutionService = mock(CapabilityToolExecutionService.class);
    private final CapabilityScannerClient scannerClient = mock(CapabilityScannerClient.class);
    private final CapabilityScanProjectCatalogService service =
            new CapabilityScanProjectCatalogService(
                    scanProjectMapper,
                    scanProjectToolMapper,
                    scanModuleMapper,
                    semanticDocMapper,
                    registryCredentialMapper,
                    registrySecurityService,
                    scanProjectBlockerService,
                    toolExecutionService,
                    toolDefinitionMapper,
                    scannerClient,
                    new ObjectMapper());

    @Test
    void listsScanProjectsByRecentUpdate() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setName("Orders");
        when(scanProjectMapper.selectList(any())).thenReturn(List.of(project));

        List<ScanProjectEntity> result = service.list();

        assertEquals(List.of(project), result);
        verify(scanProjectMapper).selectList(any());
    }

    @Test
    void createsScanProjectWithCatalogDefaults() {
        AtomicReference<ScanProjectEntity> inserted = new AtomicReference<>();
        when(scanProjectMapper.selectOne(any())).thenReturn(null);
        when(scanProjectMapper.insert(any())).thenAnswer(invocation -> {
            ScanProjectEntity entity = invocation.getArgument(0);
            entity.setId(9L);
            inserted.set(entity);
            return 1;
        });

        ScanProjectEntity result = service.create(new CapabilityScanProjectCatalogService.ScanProjectUpsertRequest(
                " Orders API ",
                "",
                "",
                "",
                "Team A",
                "",
                "https:api.example.com",
                "v1",
                "/openapi.json",
                "",
                " spec.yaml "
        ));

        assertEquals(9L, result.getId());
        assertEquals("Orders API", inserted.get().getName());
        assertEquals("orders-api", inserted.get().getProjectCode());
        assertEquals("SCAN", inserted.get().getProjectKind());
        assertEquals("default", inserted.get().getEnvironment());
        assertEquals("Team A", inserted.get().getOwner());
        assertEquals("PRIVATE", inserted.get().getVisibility());
        assertEquals("https://api.example.com", inserted.get().getBaseUrl());
        assertEquals("/v1", inserted.get().getContextPath());
        assertEquals("/openapi.json", inserted.get().getScanPath());
        assertEquals("openapi", inserted.get().getScanType());
        assertEquals("spec.yaml", inserted.get().getSpecFile());
        assertEquals(0, inserted.get().getToolCount());
        assertEquals("created", inserted.get().getStatus());
        assertEquals("none", inserted.get().getAuthType());
        assertNotNull(inserted.get().getAiCodingAccessKey());
        assertFalse(inserted.get().getAiCodingAccessKey().isBlank());
        assertEquals(true, inserted.get().getAiCodingAccessEnabled());
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new CapabilityScanProjectCatalogService.ScanProjectUpsertRequest(
                        " ",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "https://api.example.com",
                        null,
                        "/openapi.json",
                        null,
                        null
                )));
    }

    @Test
    void rejectsDuplicateName() {
        ScanProjectEntity existing = new ScanProjectEntity();
        existing.setId(3L);
        when(scanProjectMapper.selectOne(any())).thenReturn(existing);

        assertThrows(IllegalArgumentException.class,
                () -> service.create(new CapabilityScanProjectCatalogService.ScanProjectUpsertRequest(
                        "Orders",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "https://api.example.com",
                        null,
                        "/openapi.json",
                        null,
                        null
                )));
    }

    @Test
    void buildsDiffSummaryFromScanTools() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of(
                tool(1L, "GET", "/orders", "", "", null),
                tool(2L, "GET", "/orders", "List orders", "", 9L),
                tool(3L, null, null, "By source", "AI description", null)
        ));

        CapabilityScanProjectCatalogService.ScanDiffSummary summary = service.diffSummary(7L);

        assertEquals(7L, summary.projectId());
        assertEquals(3, summary.toolCount());
        assertEquals(1, summary.promotedCount());
        assertEquals(1, summary.missingDescriptionCount());
        assertEquals(2, summary.missingAiDescriptionCount());
        assertEquals(1, summary.duplicateStableKeyCount());
        assertEquals("GET /orders", summary.duplicates().get(0).stableKey());
        assertEquals(List.of(1L, 2L), summary.duplicates().get(0).scanToolIds());
    }

    @Test
    void rejectsDiffSummaryForMissingProject() {
        when(scanProjectMapper.selectById(404L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.diffSummary(404L));
    }

    @Test
    void listsScanProjectToolsForExistingProject() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        ScanProjectToolEntity tool = tool(11L, "POST", "/orders", "Create order", "AI description", 99L);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of(tool));

        List<ScanProjectToolEntity> result = service.listTools(7L);

        assertEquals(List.of(tool), result);
        verify(scanProjectToolMapper).selectList(any());
    }

    @Test
    void rejectsToolListForMissingProject() {
        when(scanProjectMapper.selectById(404L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.listTools(404L));
    }

    @Test
    void getsScanProjectToolForExistingProject() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        ScanProjectToolEntity tool = tool(11L, "POST", "/orders", "Create order", "AI description", 99L);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(tool);

        ScanProjectToolEntity result = service.getTool(7L, 11L);

        assertEquals(tool, result);
        verify(scanProjectToolMapper).selectOne(any());
    }

    @Test
    void rejectsToolDetailForMissingProject() {
        when(scanProjectMapper.selectById(404L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.getTool(404L, 11L));
    }

    @Test
    void rejectsToolDetailWhenToolDoesNotBelongToProject() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.getTool(7L, 404L));
    }

    @Test
    void updatesScanProjectToolEditableFields() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        ScanProjectToolEntity existing = tool(11L, "GET", "/orders", "Old", "AI description", 99L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(existing);

        ScanProjectToolEntity result = service.updateTool(7L, 11L, new CapabilityScanProjectCatalogService.ScanProjectToolUpsertRequest(
                "orders_create",
                "Create order",
                List.of(new ToolDefinitionParameter("body", "object", "request", true, "body")),
                "code",
                "com.example.OrderController#create",
                "POST",
                "https://api.example.com",
                "/api",
                "/orders",
                "OrderCreateRequest",
                "OrderDTO",
                true,
                false,
                true
        ));

        assertEquals(existing, result);
        assertEquals("orders_create", existing.getName());
        assertEquals("Create order", existing.getDescription());
        assertEquals("POST", existing.getHttpMethod());
        assertEquals("/api", existing.getContextPath());
        assertEquals("/orders", existing.getEndpointPath());
        assertEquals(false, existing.getAgentVisible());
        assertEquals(true, existing.getLightweightEnabled());
        verify(scanProjectToolMapper).updateById(existing);
    }

    @Test
    void rejectsScanProjectToolUpdateWithBlankName() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(tool(11L, "GET", "/orders", "Old", "AI", null));

        assertThrows(IllegalArgumentException.class, () -> service.updateTool(7L, 11L,
                new CapabilityScanProjectCatalogService.ScanProjectToolUpsertRequest(
                        " ",
                        "Create order",
                        List.of(),
                        "code",
                        null,
                        "POST",
                        null,
                        null,
                        "/orders",
                        null,
                        null,
                        true,
                        true,
                        false
                )));
    }

    @Test
    void togglesScanProjectToolEnabledFlag() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        ScanProjectToolEntity existing = tool(11L, "GET", "/orders", "Old", "AI description", null);
        existing.setEnabled(true);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(existing);

        ScanProjectToolEntity result = service.toggleTool(7L, 11L, false);

        assertEquals(existing, result);
        assertEquals(false, existing.getEnabled());
        verify(scanProjectToolMapper).updateById(existing);
    }

    @Test
    void testsScanProjectToolThroughExecutionService() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        ScanProjectToolEntity existing = tool(11L, "POST", "/orders", "Create order", "AI description", null);
        existing.setEnabled(true);
        existing.setBaseUrl("https://api.example.com");
        existing.setContextPath("/api");
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(existing);
        Map<String, Object> args = Map.of("orderNo", "A001");
        Map<String, Object> expected = Map.of("success", true, "data", Map.of("status", "CREATED"));
        when(toolExecutionService.execute(existing, Map.of("input", args))).thenReturn(expected);

        Map<String, Object> result = service.testTool(7L, 11L, args);

        assertEquals(expected, result);
        verify(toolExecutionService).execute(existing, Map.of("input", args));
    }

    @Test
    void scansProjectThroughKnowledgeScannerAndPersistsTools() {
        ScanProjectEntity project = project();
        project.setScanPath("D:/demo/openapi.json");
        project.setScanType("openapi");
        project.setContextPath("/api");
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of());
        when(scannerClient.scanOpenApi(any())).thenReturn(okManifest(List.of(
                new CapabilityScannerClient.ToolData(
                        "createOrder",
                        "Create order",
                        List.of(new CapabilityScannerClient.ToolParameterData(
                                "body",
                                "object",
                                "request body",
                                true,
                                "body",
                                List.of(),
                                Map.of("schema", "OrderCreateRequest"))),
                        new CapabilityScannerClient.ToolSourceData("openapi", "orders.yaml#createOrder"),
                        "POST",
                        "/orders",
                        "OrderCreateRequest",
                        "OrderDTO",
                        Map.of("agentVisible", true)))));

        CapabilityScanProjectCatalogService.ScanResult result = service.scan(7L);

        assertEquals(7L, result.projectId());
        assertEquals("Orders", result.projectName());
        assertEquals(1, result.toolCount());
        assertEquals(List.of("orders__create_order"), result.toolNames());
        verify(scannerClient).scanOpenApi(any());
        verify(scanProjectToolMapper).insert(any());
        verify(scanProjectMapper).updateById(project);
    }

    @Test
    void rejectsInitialScanWhenProjectAlreadyHasScanRows() {
        ScanProjectEntity project = project();
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of(tool(11L, "GET", "/orders", "old", "AI", null)));

        assertThrows(IllegalArgumentException.class, () -> service.scan(7L));
    }

    @Test
    void rescansProjectAndMarksMissingRowsRemoved() {
        ScanProjectEntity project = project();
        ScanProjectToolEntity stale = tool(11L, "GET", "/legacy", "Legacy", "AI", 501L);
        stale.setProjectId(7L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectBlockerService.analyze(7L)).thenReturn(new ScanProjectBlockers(false, List.of(), List.of(), List.of()));
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of(stale), List.of(stale));
        when(scannerClient.scanOpenApi(any())).thenReturn(okManifest(List.of()));

        CapabilityScanProjectCatalogService.ScanResult result = service.rescan(7L);

        assertEquals(0, result.toolCount());
        assertEquals(true, stale.getRemovedFromSource());
        assertNotNull(stale.getRemovedAt());
        verify(scanProjectToolMapper).updateById(stale);
        verify(scanProjectMapper).updateById(project);
    }

    @Test
    void rescanSingleToolKeepsIdentityAndUpdatesManifestFields() {
        ScanProjectEntity project = project();
        project.setContextPath("/api");
        ScanProjectToolEntity existing = tool(11L, "POST", "/orders", "Old description", "AI", null);
        existing.setProjectId(7L);
        existing.setName("orders_create");
        existing.setEnabled(true);
        existing.setAgentVisible(false);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(existing);
        when(scannerClient.scanOpenApi(any())).thenReturn(okManifest(List.of(
                new CapabilityScannerClient.ToolData(
                        "ignoredManifestName",
                        "Create order",
                        List.of(),
                        new CapabilityScannerClient.ToolSourceData("openapi", "orders.yaml#createOrder"),
                        "POST",
                        "/orders",
                        "OrderCreateRequest",
                        "OrderDTO",
                        Map.of("agentVisible", true)))));

        ScanProjectToolEntity result = service.rescanSingleTool(7L, 11L);

        assertEquals(existing, result);
        assertEquals("orders_create", existing.getName());
        assertEquals("Create order", existing.getDescription());
        assertEquals("OrderCreateRequest", existing.getRequestBodyType());
        assertEquals(true, existing.getEnabled());
        assertEquals(false, existing.getAgentVisible());
        assertEquals(false, existing.getRemovedFromSource());
        verify(scanProjectToolMapper).updateById(existing);
    }

    @Test
    void reconcilesScanProjectToolLinkStatuses() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        ScanProjectToolEntity notLinked = tool(11L, "POST", "/orders", "Create order", "AI", null);
        ScanProjectToolEntity inSync = tool(12L, "GET", "/orders", "List orders", "AI", 99L);
        ScanProjectToolEntity pendingUpdate = tool(13L, "POST", "/orders/{id}", "Update order", "AI", 100L);
        ScanProjectToolEntity removed = tool(14L, "DELETE", "/orders/{id}", "Delete order", "AI", 101L);
        removed.setRemovedFromSource(true);
        ScanProjectToolEntity globalMissing = tool(15L, "GET", "/orders/{id}", "Get order", "AI", 102L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of(
                notLinked,
                inSync,
                pendingUpdate,
                removed,
                globalMissing
        ));
        when(toolDefinitionMapper.selectBatchIds(any())).thenReturn(List.of(
                globalTool(99L, inSync),
                globalTool(100L, pendingUpdate)
        ));

        CapabilityScanProjectCatalogService.ToolReconcileSummary summary = service.reconcileTools(7L);

        assertEquals(0, summary.sdkMirrorsEnsured());
        assertEquals(1, summary.notLinked());
        assertEquals(1, summary.inSync());
        assertEquals(1, summary.pendingUpdate());
        assertEquals(1, summary.apiRemovedStale());
        assertEquals(1, summary.globalMissing());
        assertEquals(0, summary.sdkReviewPendingRows());
    }

    @Test
    void promotesScanProjectToolToGlobalTool() {
        ScanProjectEntity project = project();
        ScanProjectToolEntity scanTool = tool(11L, "POST", "/orders", "Create order", "AI", null);
        scanTool.setProjectId(7L);
        scanTool.setBaseUrl("https://api.example.com");
        scanTool.setContextPath("/api");
        scanTool.setParametersJson("[{\"name\":\"body\",\"type\":\"object\"}]");
        AtomicReference<ToolDefinitionEntity> inserted = new AtomicReference<>();
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(scanTool);
        when(toolDefinitionMapper.insert(any())).thenAnswer(invocation -> {
            ToolDefinitionEntity entity = invocation.getArgument(0);
            entity.setId(501L);
            inserted.set(entity);
            return 1;
        });

        CapabilityScanProjectCatalogService.PromotedGlobalTool result = service.promoteTool(7L, 11L);

        assertEquals(501L, result.globalToolId());
        assertEquals("tool11", result.globalToolName());
        assertEquals(501L, scanTool.getGlobalToolDefinitionId());
        assertEquals("TOOL", inserted.get().getKind());
        assertEquals("scanner", inserted.get().getSource());
        assertEquals(7L, inserted.get().getProjectId());
        assertEquals("orders", inserted.get().getProjectCode());
        assertEquals("orders:tool11", inserted.get().getQualifiedName());
        assertEquals("PROJECT", inserted.get().getVisibility());
        assertEquals("https://api.example.com", inserted.get().getBaseUrl());
        verify(scanProjectToolMapper).updateById(scanTool);
    }

    @Test
    void pushesScanProjectToolFieldsToLinkedGlobalTool() {
        ScanProjectEntity project = project();
        ScanProjectToolEntity scanTool = tool(11L, "POST", "/orders", "Create order", "AI", 501L);
        scanTool.setProjectId(7L);
        scanTool.setBaseUrl("https://api.example.com");
        scanTool.setContextPath("/api");
        ToolDefinitionEntity global = globalTool(501L, scanTool);
        global.setDescription("Old description");
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(scanTool);
        when(toolDefinitionMapper.selectById(501L)).thenReturn(global);

        ScanProjectToolEntity result = service.pushToolToGlobal(7L, 11L);

        assertEquals(scanTool, result);
        assertEquals("Create order", global.getDescription());
        assertEquals("scanner", global.getSource());
        assertEquals("orders:tool11", global.getQualifiedName());
        verify(toolDefinitionMapper).updateById(global);
    }

    @Test
    void unpromotesScanProjectToolFromGlobalTool() {
        ScanProjectEntity project = project();
        ScanProjectToolEntity scanTool = tool(11L, "POST", "/orders", "Create order", "AI", 501L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(scanTool);

        ScanProjectToolEntity result = service.unpromoteTool(7L, 11L);

        assertEquals(scanTool, result);
        assertEquals(null, scanTool.getGlobalToolDefinitionId());
        verify(toolDefinitionMapper).deleteById(501L);
        verify(scanProjectToolMapper).updateById(scanTool);
    }

    @Test
    void promotesModuleScanToolsToGlobalTools() {
        ScanProjectEntity project = project();
        ScanProjectToolEntity first = tool(11L, "POST", "/orders", "Create order", "AI", null);
        first.setProjectId(7L);
        first.setModuleId(3L);
        ScanProjectToolEntity alreadyLinked = tool(12L, "GET", "/orders", "List orders", "AI", 99L);
        alreadyLinked.setProjectId(7L);
        alreadyLinked.setModuleId(3L);
        AtomicReference<ToolDefinitionEntity> inserted = new AtomicReference<>();
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of(first, alreadyLinked));
        when(toolDefinitionMapper.insert(any())).thenAnswer(invocation -> {
            ToolDefinitionEntity entity = invocation.getArgument(0);
            entity.setId(601L);
            inserted.set(entity);
            return 1;
        });

        CapabilityScanProjectCatalogService.BatchPromoteToToolsResult result =
                service.promoteModuleTools(7L, 3L);

        assertEquals(1, result.promotedCount());
        assertEquals(List.of(new CapabilityScanProjectCatalogService.PromotedGlobalTool(601L, "tool11")), result.items());
        assertEquals(601L, first.getGlobalToolDefinitionId());
        assertEquals("orders:tool11", inserted.get().getQualifiedName());
        verify(scanProjectToolMapper).updateById(first);
    }

    @Test
    void returnsOperationBlockersForExistingProject() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        ScanProjectBlockers blockers = new ScanProjectBlockers(
                true,
                List.of("orders_create"),
                List.of(),
                List.of(new ScanProjectBlockers.AgentRef("agent-1", "Team Assistant")));
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectBlockerService.analyze(7L)).thenReturn(blockers);

        ScanProjectBlockers result = service.operationBlockers(7L);

        assertEquals(blockers, result);
    }

    @Test
    void rejectsOperationBlockersForMissingProject() {
        when(scanProjectMapper.selectById(404L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.operationBlockers(404L));
    }

    private ScanProjectToolEntity tool(Long id,
                                       String method,
                                       String path,
                                       String description,
                                       String aiDescription,
                                       Long globalToolDefinitionId) {
        ScanProjectToolEntity tool = new ScanProjectToolEntity();
        tool.setId(id);
        tool.setHttpMethod(method);
        tool.setEndpointPath(path);
        tool.setSourceLocation("com.example.Controller#" + id);
        tool.setName("tool" + id);
        tool.setDescription(description);
        tool.setAiDescription(aiDescription);
        tool.setGlobalToolDefinitionId(globalToolDefinitionId);
        return tool;
    }

    private ScanProjectEntity project() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setName("Orders");
        project.setProjectCode("orders");
        project.setVisibility("PROJECT");
        return project;
    }

    private ToolDefinitionEntity globalTool(Long id, ScanProjectToolEntity scanTool) {
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setId(id);
        tool.setName(scanTool.getName());
        tool.setDescription(id == 100L ? scanTool.getDescription() + " old" : scanTool.getDescription());
        tool.setParametersJson(scanTool.getParametersJson());
        tool.setHttpMethod(scanTool.getHttpMethod());
        tool.setBaseUrl(scanTool.getBaseUrl());
        tool.setContextPath(scanTool.getContextPath());
        tool.setEndpointPath(scanTool.getEndpointPath());
        tool.setRequestBodyType(scanTool.getRequestBodyType());
        tool.setResponseType(scanTool.getResponseType());
        tool.setEnabled(scanTool.getEnabled());
        tool.setAgentVisible(scanTool.getAgentVisible());
        tool.setLightweightEnabled(scanTool.getLightweightEnabled());
        return tool;
    }

    private com.enterprise.ai.common.dto.ApiResult<CapabilityScannerClient.ManifestData> okManifest(
            List<CapabilityScannerClient.ToolData> tools) {
        CapabilityScannerClient.ProjectData project = new CapabilityScannerClient.ProjectData("orders", "https://api.example.com", "/api");
        return com.enterprise.ai.common.dto.ApiResult.ok(new CapabilityScannerClient.ManifestData(project, tools));
    }
}
