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
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceMapper;
import com.enterprise.ai.agent.registry.CapabilitySnapshotEntity;
import com.enterprise.ai.agent.registry.CapabilitySnapshotMapper;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistryCredentialMapper;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private final CapabilityScannerClient scannerClient = mock(CapabilityScannerClient.class);
    private final ProjectInstanceMapper projectInstanceMapper = mock(ProjectInstanceMapper.class);
    private final CapabilitySnapshotMapper capabilitySnapshotMapper = mock(CapabilitySnapshotMapper.class);
    private final ControllerScanHttpApiIntakeService controllerHttpApiIntake = mock(ControllerScanHttpApiIntakeService.class);
    private final OpenApiScanHttpApiIntakeService openApiHttpApiIntake = mock(OpenApiScanHttpApiIntakeService.class);
    private final CapabilityScanProjectCatalogService service =
            new CapabilityScanProjectCatalogService(
                    scanProjectMapper,
                    scanProjectToolMapper,
                    scanModuleMapper,
                    semanticDocMapper,
                    registryCredentialMapper,
                    registrySecurityService,
                    scanProjectBlockerService,
                    toolDefinitionMapper,
                    scannerClient,
                    projectInstanceMapper,
                    capabilitySnapshotMapper,
                    new ObjectMapper(),
                    controllerHttpApiIntake,
                    openApiHttpApiIntake);

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
    void sdkAccessCheckSurfacesComputedManualSyncCallbackTarget() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        project.setBaseUrl("http://localhost:8080/");
        project.setContextPath("/orders-api");
        project.setAiCodingAccessEnabled(true);
        project.setAiCodingAccessKey("aic_demo");
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("orders");
        credential.setStatus("ACTIVE");
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential(any())).thenReturn(java.util.Optional.of(credential));
        when(projectInstanceMapper.selectOne(any())).thenReturn(null);

        CapabilityScanProjectCatalogService.SdkAccessCheckResponse response = service.sdkAccessCheck(7L);

        CapabilityScanProjectCatalogService.SdkAccessCheckItem callback = response.checks().stream()
                .filter(check -> "SDK_SYNC_CALLBACK".equals(check.key()))
                .findFirst()
                .orElseThrow();
        assertEquals("http://localhost:8080/orders-api/reachai/registry/capabilities/sync", callback.evidence());
        assertEquals("WARN", callback.status());
        assertTrue(callback.message().contains("CONFIGURED_NOT_PROBED"));
        assertTrue(callback.message().contains("回环地址"));
        assertEquals("FAIL", response.readiness().stream()
                .filter(item -> "RUNTIME_READY".equals(item.key()))
                .findFirst()
                .orElseThrow()
                .status());
        assertEquals("PENDING", response.readiness().stream()
                .filter(item -> "SDK_CALLBACK_READY".equals(item.key()))
                .findFirst()
                .orElseThrow()
                .status());
        assertEquals("PENDING", response.readiness().stream()
                .filter(item -> "CODE_READY".equals(item.key()))
                .findFirst()
                .orElseThrow()
                .status());
    }

    @Test
    void readinessFactsAndRuntimeReadyRequireOnlineStatusWithFreshHeartbeat() {
        ScanProjectEntity project = seededProject();
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential(any())).thenReturn(java.util.Optional.of(activeCredential()));

        when(projectInstanceMapper.selectOne(any())).thenReturn(instance("ONLINE", LocalDateTime.now().minusMinutes(1)));
        Map<String, Object> onlineFacts = service.readinessFacts(7L);
        assertEquals(true, onlineFacts.get("online"));
        assertEquals("PASS", runtimeReady(service.sdkAccessCheck(7L)));

        when(projectInstanceMapper.selectOne(any())).thenReturn(instance("ONLINE", LocalDateTime.now().minusMinutes(10)));
        assertEquals(false, service.readinessFacts(7L).get("online"));
        assertEquals("WARN", runtimeReady(service.sdkAccessCheck(7L)));

        when(projectInstanceMapper.selectOne(any())).thenReturn(instance("OFFLINE", LocalDateTime.now().minusMinutes(1)));
        assertEquals(false, service.readinessFacts(7L).get("online"));
        assertEquals("WARN", runtimeReady(service.sdkAccessCheck(7L)));

        when(projectInstanceMapper.selectOne(any())).thenReturn(instance("DISABLED", LocalDateTime.now().minusMinutes(1)));
        assertEquals(false, service.readinessFacts(7L).get("online"));
        assertEquals("WARN", runtimeReady(service.sdkAccessCheck(7L)));

        when(projectInstanceMapper.selectOne(any())).thenReturn(null);
        assertEquals(false, service.readinessFacts(7L).get("online"));
        assertEquals("FAIL", runtimeReady(service.sdkAccessCheck(7L)));
    }

    private ScanProjectEntity seededProject() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        project.setBaseUrl("http://localhost:8080/");
        project.setContextPath("/orders-api");
        project.setAiCodingAccessEnabled(true);
        project.setAiCodingAccessKey("aic_demo");
        return project;
    }

    private RegistryCredentialEntity activeCredential() {
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("orders");
        credential.setStatus("ACTIVE");
        return credential;
    }

    private ProjectInstanceEntity instance(String status, LocalDateTime heartbeatAt) {
        ProjectInstanceEntity entity = new ProjectInstanceEntity();
        entity.setInstanceId("inst-1");
        entity.setStatus(status);
        entity.setLastHeartbeatAt(heartbeatAt);
        return entity;
    }

    private String runtimeReady(CapabilityScanProjectCatalogService.SdkAccessCheckResponse response) {
        return response.readiness().stream()
                .filter(item -> "RUNTIME_READY".equals(item.key()))
                .findFirst()
                .orElseThrow()
                .status();
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
                        Map.of()))));

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
    void controllerRescanPrevalidatesFullHttpInventoryBeforeLegacyRowsAndObservesItAfterward() {
        ScanProjectEntity project = project();
        project.setScanType("controller");
        project.setEnvironment("prod");
        project.setContextPath("/orders-api");
        project.setLastScannedAt(LocalDateTime.now().minusHours(1));
        CapabilityScannerClient.HttpApiData httpApi = new CapabilityScannerClient.HttpApiData(
                "controller:orders#find", "src/OrdersController.java:42", "controller-r1", "GET", "/ignored",
                "/orders/{id}", List.of(), List.of(), List.of(), List.of(), null, List.of(), "UNKNOWN",
                List.of(), List.of(), "READ_ONLY");
        CapabilityScannerClient.ToolData legacyTool = new CapabilityScannerClient.ToolData(
                "findOrder", "Find order", List.of(),
                new CapabilityScannerClient.ToolSourceData("controller", "com.example.OrdersController#find"),
                "GET", "/orders/{id}", null, "Order", Map.of());
        CapabilityScannerClient.ManifestData manifest = new CapabilityScannerClient.ManifestData(
                new CapabilityScannerClient.ProjectData("untrusted-scanner-name", "https://ignored", "/ignored"),
                List.of(legacyTool), List.of(httpApi), true);
        ControllerScanHttpApiIntakeService.Plan plan = new ControllerScanHttpApiIntakeService.Plan(
                true, true, new com.enterprise.ai.capability.catalog.httpapi.HttpApiServiceScope(7L, "orders", "prod"),
                List.of());
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectBlockerService.analyze(7L)).thenReturn(new ScanProjectBlockers(false, List.of(), List.of(), List.of()));
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of());
        when(scannerClient.scanController(any())).thenReturn(com.enterprise.ai.common.dto.ApiResult.ok(manifest));
        when(controllerHttpApiIntake.prepare(project, manifest.httpApis(), true)).thenReturn(plan);

        CapabilityScanProjectCatalogService.ScanResult result = service.rescan(7L);

        assertEquals(List.of("find_order"), result.toolNames());
        ArgumentCaptor<CapabilityScannerClient.ScanRequest> request = ArgumentCaptor.forClass(CapabilityScannerClient.ScanRequest.class);
        verify(scannerClient).scanController(request.capture());
        assertNull(request.getValue().incrementalSinceEpochMs(),
                "Controller rescans need a complete inventory before they may reconcile removed source bindings");
        verify(scannerClient, never()).scanOpenApi(any());
        InOrder order = org.mockito.Mockito.inOrder(controllerHttpApiIntake, scanProjectToolMapper);
        order.verify(controllerHttpApiIntake).prepare(project, manifest.httpApis(), true);
        order.verify(scanProjectToolMapper).insert(any());
        order.verify(controllerHttpApiIntake).observe(plan);
    }

    @Test
    void openApiRescanPrevalidatesFullHttpInventoryBeforeLegacyRowsAndObservesItAfterward() {
        ScanProjectEntity project = project();
        project.setScanType("openapi");
        project.setEnvironment("prod");
        project.setContextPath("/orders-api");
        project.setLastScannedAt(LocalDateTime.now().minusHours(1));
        CapabilityScannerClient.HttpApiData httpApi = new CapabilityScannerClient.HttpApiData(
                "openapi:contracts/orders.yaml:hash", "contracts/orders.yaml#/paths/~1orders/get", "scan-r1",
                "GET", "/untrusted", "/orders", List.of(), List.of(), List.of(), List.of(), null, List.of(),
                "UNKNOWN", List.of(), List.of(), "READ_ONLY");
        CapabilityScannerClient.ToolData legacyTool = new CapabilityScannerClient.ToolData(
                "findOrder", "Find order", List.of(),
                new CapabilityScannerClient.ToolSourceData("openapi", "orders.yaml#/paths/~1orders/get"),
                "GET", "/orders", null, "Order", Map.of());
        CapabilityScannerClient.ManifestData manifest = new CapabilityScannerClient.ManifestData(
                new CapabilityScannerClient.ProjectData("untrusted-scanner-name", "https://ignored", "/ignored"),
                List.of(legacyTool), List.of(httpApi), true);
        OpenApiScanHttpApiIntakeService.Plan plan = new OpenApiScanHttpApiIntakeService.Plan(
                true, true, new com.enterprise.ai.capability.catalog.httpapi.HttpApiServiceScope(7L, "orders", "prod"),
                List.of());
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectBlockerService.analyze(7L)).thenReturn(new ScanProjectBlockers(false, List.of(), List.of(), List.of()));
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of());
        when(scannerClient.scanOpenApi(any())).thenReturn(com.enterprise.ai.common.dto.ApiResult.ok(manifest));
        when(openApiHttpApiIntake.prepare(project, manifest.httpApis(), true)).thenReturn(plan);

        CapabilityScanProjectCatalogService.ScanResult result = service.rescan(7L);

        assertEquals(List.of("orders__find_order"), result.toolNames());
        ArgumentCaptor<CapabilityScannerClient.ScanRequest> request = ArgumentCaptor.forClass(CapabilityScannerClient.ScanRequest.class);
        verify(scannerClient).scanOpenApi(request.capture());
        assertNull(request.getValue().incrementalSinceEpochMs(),
                "OpenAPI rescans need a full document inventory before they may reconcile removed source bindings");
        verify(scannerClient, never()).scanController(any());
        InOrder order = org.mockito.Mockito.inOrder(openApiHttpApiIntake, scanProjectToolMapper);
        order.verify(openApiHttpApiIntake).prepare(project, manifest.httpApis(), true);
        order.verify(scanProjectToolMapper).insert(any());
        order.verify(openApiHttpApiIntake).observe(plan);
    }

    @Test
    void singleToolControllerRefreshDoesNotReconcileHttpApiSources() {
        ScanProjectEntity project = project();
        project.setScanType("controller");
        project.setContextPath("/orders-api");
        ScanProjectToolEntity existing = tool(11L, "GET", "/orders/{id}", "Old description", "AI", null);
        existing.setProjectId(7L);
        existing.setName("find_order");
        CapabilityScannerClient.ToolData scannerTool = new CapabilityScannerClient.ToolData(
                "findOrder", "Fresh description", List.of(),
                new CapabilityScannerClient.ToolSourceData("controller", "com.example.OrdersController#find"),
                "GET", "/orders/{id}", null, "Order", Map.of());
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(existing);
        when(scannerClient.scanController(any())).thenReturn(okManifest(List.of(scannerTool)));

        ScanProjectToolEntity refreshed = service.rescanSingleTool(7L, 11L);

        assertEquals(existing, refreshed);
        assertEquals("Fresh description", existing.getDescription());
        verifyNoInteractions(controllerHttpApiIntake);
        verifyNoInteractions(openApiHttpApiIntake);
    }

    @Test
    void singleToolOpenApiRefreshDoesNotReconcileHttpApiSources() {
        ScanProjectEntity project = project();
        project.setScanType("openapi");
        project.setContextPath("/orders-api");
        ScanProjectToolEntity existing = tool(11L, "GET", "/orders/{id}", "Old description", "AI", null);
        existing.setProjectId(7L);
        existing.setName("find_order");
        CapabilityScannerClient.ToolData scannerTool = new CapabilityScannerClient.ToolData(
                "findOrder", "Fresh description", List.of(),
                new CapabilityScannerClient.ToolSourceData("openapi", "orders.yaml#/paths/~1orders~1{id}/get"),
                "GET", "/orders/{id}", null, "Order", Map.of());
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(existing);
        when(scannerClient.scanOpenApi(any())).thenReturn(okManifest(List.of(scannerTool)));

        ScanProjectToolEntity refreshed = service.rescanSingleTool(7L, 11L);

        assertEquals(existing, refreshed);
        assertEquals("Fresh description", existing.getDescription());
        verifyNoInteractions(controllerHttpApiIntake, openApiHttpApiIntake);
    }

    @Test
    void controllerSourceOperationsDeclareReadCommittedProjectTransactions() throws Exception {
        Transactional scan = CapabilityScanProjectCatalogService.class.getMethod("scan", Long.class)
                .getAnnotation(Transactional.class);
        Transactional rescan = CapabilityScanProjectCatalogService.class.getMethod("rescan", Long.class)
                .getAnnotation(Transactional.class);

        assertNotNull(scan);
        assertNotNull(rescan);
        assertEquals(Isolation.READ_COMMITTED, scan.isolation());
        assertEquals(Isolation.READ_COMMITTED, rescan.isolation());
    }

    @Test
    void scannerWireDistinguishesOldMissingInventoryFromExplicitEmptyAndPartialInventory() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String project = "{\"name\":\"orders\",\"baseUrl\":\"http://scanner\",\"contextPath\":\"/ignored\"}";

        CapabilityScannerClient.ManifestData oldWire = mapper.readValue(
                "{\"project\":" + project + ",\"tools\":[]}", CapabilityScannerClient.ManifestData.class);
        CapabilityScannerClient.ManifestData explicitEmpty = mapper.readValue(
                "{\"project\":" + project + ",\"tools\":[],\"httpApis\":[],\"httpApiInventoryComplete\":true}",
                CapabilityScannerClient.ManifestData.class);
        CapabilityScannerClient.ManifestData partial = mapper.readValue(
                "{\"project\":" + project + ",\"tools\":[],\"httpApis\":[],\"httpApiInventoryComplete\":false}",
                CapabilityScannerClient.ManifestData.class);

        assertNull(oldWire.httpApis());
        assertNull(oldWire.httpApiInventoryComplete());
        assertEquals(List.of(), explicitEmpty.httpApis());
        assertEquals(true, explicitEmpty.httpApiInventoryComplete());
        assertEquals(List.of(), partial.httpApis());
        assertEquals(false, partial.httpApiInventoryComplete());
    }

    @Test
    void rescanSingleToolKeepsIdentityAndUpdatesManifestFields() {
        ScanProjectEntity project = project();
        project.setContextPath("/api");
        ScanProjectToolEntity existing = tool(11L, "POST", "/orders", "Old description", "AI", null);
        existing.setProjectId(7L);
        existing.setName("orders_create");
        existing.setEnabled(true);
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
                        Map.of()))));

        ScanProjectToolEntity result = service.rescanSingleTool(7L, 11L);

        assertEquals(existing, result);
        assertEquals("orders_create", existing.getName());
        assertEquals("Create order", existing.getDescription());
        assertEquals("OrderCreateRequest", existing.getRequestBodyType());
        assertEquals(true, existing.getEnabled());
        assertEquals(false, existing.getRemovedFromSource());
        verify(scanProjectToolMapper).updateById(existing);
    }

    @Test
    void reconcilesOnlyUnlinkedSourceRowsWithoutTouchingAnyProjection() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        ScanProjectToolEntity controller = tool(11L, "GET", "/orders/{id}", "Controller order", "AI", null);
        ScanProjectToolEntity openapi = tool(12L, "GET", "/orders/{id}", "OpenAPI order", "AI", null);
        controller.setRemovedFromSource(true);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of(controller, openapi));

        CapabilityScanProjectCatalogService.ToolReconcileSummary summary = service.reconcileTools(7L);

        assertEquals(2, summary.notLinked());
        assertEquals(0, summary.sdkMirrorsEnsured());
        assertEquals(0, summary.inSync());
        assertEquals(0, summary.pendingUpdate());
        assertEquals(0, summary.apiRemovedStale());
        assertEquals(0, summary.globalMissing());
        assertEquals(0, summary.sdkReviewPendingRows());
        verifyNoInteractions(toolDefinitionMapper, scannerClient, controllerHttpApiIntake, openApiHttpApiIntake);
        verify(scanProjectToolMapper, never()).insert(any(ScanProjectToolEntity.class));
        verify(scanProjectToolMapper, never()).updateById(any(ScanProjectToolEntity.class));
        verify(scanProjectToolMapper, never()).deleteById(any(Long.class));
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
    void sdkAccessCheckUsesSuccessfulSignedCallbackSnapshotAsCallbackEvidence() {
        ScanProjectEntity project = seededProject();
        CapabilitySnapshotEntity callbackSnapshot = new CapabilitySnapshotEntity();
        callbackSnapshot.setId(19L);
        callbackSnapshot.setProjectId(7L);
        callbackSnapshot.setSource("SDK_CALLBACK");
        callbackSnapshot.setCreatedAt(LocalDateTime.of(2026, 7, 27, 9, 30));
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential(any())).thenReturn(java.util.Optional.of(activeCredential()));
        when(projectInstanceMapper.selectOne(any())).thenReturn(instance(
                "ONLINE", LocalDateTime.now().minusMinutes(1)));
        when(capabilitySnapshotMapper.selectOne(any())).thenReturn(callbackSnapshot);

        CapabilityScanProjectCatalogService.SdkAccessCheckResponse response = service.sdkAccessCheck(7L);

        CapabilityScanProjectCatalogService.SdkAccessCheckItem callback = response.checks().stream()
                .filter(check -> "SDK_SYNC_CALLBACK".equals(check.key()))
                .findFirst()
                .orElseThrow();
        assertEquals("PASS", callback.status());
        assertTrue(callback.message().contains("2026-07-27T09:30"));
        assertEquals("PASS", response.readiness().stream()
                .filter(item -> "SDK_CALLBACK_READY".equals(item.key()))
                .findFirst()
                .orElseThrow()
                .status());
        assertTrue(response.readiness().stream()
                .noneMatch(item -> "E2E_READY".equals(item.key())));
        assertEquals("SDK 回调闭环", response.readiness().stream()
                .filter(item -> "SDK_CALLBACK_READY".equals(item.key()))
                .findFirst()
                .orElseThrow()
                .label());
        Map<String, Object> readinessFacts = service.readinessFacts(7L);
        assertEquals("PASS", readinessFacts.get("sdkCallbackStatus"));
        assertTrue(String.valueOf(readinessFacts.get("sdkCallbackMessage"))
                .contains("2026-07-27T09:30"));
        assertEquals("PASS", response.overallStatus());
    }

    @Test
    void sdkAccessCheckDoesNotReuseCallbackEvidenceFromBeforeConfigurationUpdate() {
        ScanProjectEntity project = seededProject();
        project.setUpdateTime(LocalDateTime.of(2026, 7, 27, 10, 0));
        CapabilitySnapshotEntity callbackSnapshot = new CapabilitySnapshotEntity();
        callbackSnapshot.setId(19L);
        callbackSnapshot.setProjectId(7L);
        callbackSnapshot.setSource("SDK_CALLBACK");
        callbackSnapshot.setCreatedAt(LocalDateTime.of(2026, 7, 27, 9, 30));
        callbackSnapshot.setPayloadJson(callbackPayload("http://localhost:9090", "/orders-api"));
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential(any())).thenReturn(java.util.Optional.of(activeCredential()));
        when(projectInstanceMapper.selectOne(any())).thenReturn(instance(
                "ONLINE", LocalDateTime.now().minusMinutes(1)));
        when(capabilitySnapshotMapper.selectOne(any())).thenReturn(callbackSnapshot);

        CapabilityScanProjectCatalogService.SdkAccessCheckResponse response = service.sdkAccessCheck(7L);

        CapabilityScanProjectCatalogService.SdkAccessCheckItem callback = response.checks().stream()
                .filter(check -> "SDK_SYNC_CALLBACK".equals(check.key()))
                .findFirst()
                .orElseThrow();
        assertEquals("WARN", callback.status());
        assertTrue(callback.message().contains("STALE_EVIDENCE"));
        assertEquals("PENDING", response.readiness().stream()
                .filter(item -> "SDK_CALLBACK_READY".equals(item.key()))
                .findFirst()
                .orElseThrow()
                .status());
        assertEquals("WARN", response.overallStatus());
    }

    @Test
    void sdkAccessCheckKeepsCallbackEvidenceAfterIdempotentReregistration() {
        ScanProjectEntity project = seededProject();
        project.setUpdateTime(LocalDateTime.of(2026, 7, 27, 10, 0));
        CapabilitySnapshotEntity callbackSnapshot = new CapabilitySnapshotEntity();
        callbackSnapshot.setId(19L);
        callbackSnapshot.setProjectId(7L);
        callbackSnapshot.setSource("SDK_CALLBACK");
        callbackSnapshot.setCreatedAt(LocalDateTime.of(2026, 7, 27, 9, 30));
        callbackSnapshot.setPayloadJson(callbackPayload("http://localhost:8080/", "/orders-api"));
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential(any())).thenReturn(java.util.Optional.of(activeCredential()));
        when(projectInstanceMapper.selectOne(any())).thenReturn(instance(
                "ONLINE", LocalDateTime.now().minusMinutes(1)));
        when(capabilitySnapshotMapper.selectOne(any())).thenReturn(callbackSnapshot);

        CapabilityScanProjectCatalogService.SdkAccessCheckResponse response = service.sdkAccessCheck(7L);

        CapabilityScanProjectCatalogService.SdkAccessCheckItem callback = response.checks().stream()
                .filter(check -> "SDK_SYNC_CALLBACK".equals(check.key()))
                .findFirst()
                .orElseThrow();
        assertEquals("PASS", callback.status());
        assertEquals("PASS", response.readiness().stream()
                .filter(item -> "SDK_CALLBACK_READY".equals(item.key()))
                .findFirst()
                .orElseThrow()
                .status());
    }

    private String callbackPayload(String baseUrl, String contextPath) {
        return """
                {
                  "syncId":"sync-1",
                  "source":"SDK_CALLBACK",
                  "apply":false,
                  "capabilities":[
                    {
                      "name":"orders.search",
                      "baseUrl":"%s",
                      "contextPath":"%s"
                    }
                  ]
                }
                """.formatted(baseUrl, contextPath);
    }







    @Test
    void returnsOperationBlockersForExistingProject() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        ScanProjectBlockers blockers = new ScanProjectBlockers(
                true,
                List.of("orders_create"),
                List.of(new ScanProjectBlockers.AgentRef("agent-1", "Team Assistant")), List.of());
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectBlockerService.analyze(7L)).thenReturn(blockers);

        ScanProjectBlockers result = service.operationBlockers(7L, ScanProjectBlockers.Operation.RESCAN);

        assertEquals(blockers, result);
    }

    @Test
    void deletionUsesOwnerBlockersAndDoesNotDeleteAnyProjectData() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        ScanProjectBlockers blockers = new ScanProjectBlockers(true, List.of(), List.of(),
                List.of(new ScanProjectBlockers.AssetRef("BUSINESS_METHOD", 21L, "orders:read", "查询订单")));
        when(scanProjectBlockerService.analyzeDeletion(7L)).thenReturn(blockers);

        var failure = assertThrows(CapabilityScanProjectCatalogService.ScanProjectBlockedException.class,
                () -> service.delete(7L));

        assertEquals(blockers, failure.blockers());
        var order = org.mockito.Mockito.inOrder(scanProjectMapper, scanProjectBlockerService);
        order.verify(scanProjectMapper).lockCapabilityChanges(7L);
        order.verify(scanProjectMapper).selectById(7L);
        order.verify(scanProjectBlockerService).analyzeDeletion(7L);
        verify(scanProjectMapper, never()).deleteById(any(Long.class));
        verifyNoInteractions(semanticDocMapper, scanProjectToolMapper, scanModuleMapper, registryCredentialMapper);
    }

    @Test
    void deletionRefusesUnavailableBlockersAndRetainsTheProject() {
        ScanProjectEntity project = new ScanProjectEntity(); project.setId(7L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        assertThrows(IllegalStateException.class, () -> service.delete(7L));
        verify(scanProjectMapper, never()).deleteById(any(Long.class));
        verifyNoInteractions(semanticDocMapper, scanProjectToolMapper, scanModuleMapper, registryCredentialMapper);
    }

    @Test
    void deletionPreflightUsesTheSameOwnerCheckAsDeletion() {
        ScanProjectEntity project = new ScanProjectEntity(); project.setId(7L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        var blockers = new ScanProjectBlockers(true, List.of(), List.of(),
                List.of(new ScanProjectBlockers.AssetRef("HTTP_API", 31L, "orders:http:get", "GET /orders")));
        when(scanProjectBlockerService.analyzeDeletion(7L)).thenReturn(blockers);
        assertEquals(blockers, service.operationBlockers(7L, ScanProjectBlockers.Operation.DELETE));
        verify(scanProjectBlockerService, never()).analyze(any());
    }

    @Test
    void deletionWithoutAssetsOrReferencesKeepsTheEmptyProjectCleanupPath() {
        ScanProjectEntity project = new ScanProjectEntity(); project.setId(7L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectBlockerService.analyzeDeletion(7L)).thenReturn(ScanProjectBlockers.empty());
        service.delete(7L);
        verify(scanProjectMapper).deleteById(7L);
        verify(semanticDocMapper).delete(any());
        verify(scanProjectToolMapper).delete(any());
        verify(scanModuleMapper).delete(any());
        verify(registryCredentialMapper).delete(any());
    }

    @Test
    void rejectsOperationBlockersForMissingProject() {
        when(scanProjectMapper.selectById(404L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.operationBlockers(404L, ScanProjectBlockers.Operation.RESCAN));
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
        tool.setTitle("Tool " + id);
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
        tool.setTitle(scanTool.getTitle());
        tool.setDescription(id == 100L ? scanTool.getDescription() + " old" : scanTool.getDescription());
        tool.setParametersJson(scanTool.getParametersJson());
        tool.setHttpMethod(scanTool.getHttpMethod());
        tool.setBaseUrl(scanTool.getBaseUrl());
        tool.setContextPath(scanTool.getContextPath());
        tool.setEndpointPath(scanTool.getEndpointPath());
        tool.setRequestBodyType(scanTool.getRequestBodyType());
        tool.setResponseType(scanTool.getResponseType());
        tool.setEnabled(scanTool.getEnabled());
        return tool;
    }

    private com.enterprise.ai.common.dto.ApiResult<CapabilityScannerClient.ManifestData> okManifest(
            List<CapabilityScannerClient.ToolData> tools) {
        CapabilityScannerClient.ProjectData project = new CapabilityScannerClient.ProjectData("orders", "https://api.example.com", "/api");
        return com.enterprise.ai.common.dto.ApiResult.ok(new CapabilityScannerClient.ManifestData(project, tools));
    }
}
