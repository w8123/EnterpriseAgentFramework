package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleMapper;
import com.enterprise.ai.capability.catalog.enrichment.CapabilityCatalogGroupingService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceKind;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiServiceScope;
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
    private final ScannerHttpApiIntakeService scannerHttpApiIntake = mock(ScannerHttpApiIntakeService.class);
    private final ScannerHttpApiSingleSourceIntakeService singleSourceIntake = mock(ScannerHttpApiSingleSourceIntakeService.class);
    private final CapabilityCatalogGroupingService groupingService = mock(CapabilityCatalogGroupingService.class);
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
                    new ObjectMapper(),
                    scannerHttpApiIntake,
                    singleSourceIntake,
                    new CapabilityScanProjectReadinessService(projectInstanceMapper, capabilitySnapshotMapper,
                            registrySecurityService, new ObjectMapper()),
                    groupingService);

    {
        var revisions = mock(CapabilityScanRevisionService.class);
        when(revisions.revision(any())).thenReturn("unchanged-test-generation");
        service.setScanTransactions(new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        }, revisions);
    }

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

        CapabilityScanProjectReadinessService.SdkAccessCheckResponse response = service.sdkAccessCheck(7L);

        CapabilityScanProjectReadinessService.SdkAccessCheckItem callback = response.checks().stream()
                .filter(check -> "SDK_SYNC_CALLBACK".equals(check.key()))
                .findFirst()
                .orElseThrow();
        assertEquals("http://localhost:8080/orders-api/reachai/registry/capabilities/sync", callback.evidence());
        assertEquals("WARN", callback.status());
        assertTrue(callback.message().contains("CONFIGURED_NOT_PROBED"));
        assertTrue(callback.message().contains("回环地址"));
        verify(capabilitySnapshotMapper, org.mockito.Mockito.times(1)).selectOne(any());
        verify(projectInstanceMapper, org.mockito.Mockito.times(1)).selectOne(any());
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

    private String runtimeReady(CapabilityScanProjectReadinessService.SdkAccessCheckResponse response) {
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
        ScannerHttpApiIntakeService.Plan plan = new ScannerHttpApiIntakeService.Plan(
                true, true, new com.enterprise.ai.capability.catalog.httpapi.HttpApiServiceScope(7L, "orders", "prod"),
                HttpApiSourceKind.CONTROLLER_SCAN, List.of());
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectBlockerService.analyze(7L)).thenReturn(new ScanProjectBlockers(false, List.of(), List.of(), List.of()));
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of());
        when(scannerClient.scanController(any())).thenReturn(com.enterprise.ai.common.dto.ApiResult.ok(manifest));
        when(scannerHttpApiIntake.prepare(HttpApiSourceKind.CONTROLLER_SCAN, project, manifest.httpApis(), true)).thenReturn(plan);

        CapabilityScanProjectCatalogService.ScanResult result = service.rescan(7L);

        assertEquals(List.of("find_order"), result.toolNames());
        ArgumentCaptor<CapabilityScannerClient.ScanRequest> request = ArgumentCaptor.forClass(CapabilityScannerClient.ScanRequest.class);
        verify(scannerClient).scanController(request.capture());
        assertNull(request.getValue().incrementalSinceEpochMs(),
                "Controller rescans need a complete inventory before they may reconcile removed source bindings");
        verify(scannerClient, never()).scanOpenApi(any());
        InOrder order = org.mockito.Mockito.inOrder(scannerHttpApiIntake, scanProjectToolMapper);
        order.verify(scannerHttpApiIntake).prepare(HttpApiSourceKind.CONTROLLER_SCAN, project, manifest.httpApis(), true);
        order.verify(scanProjectToolMapper).insert(any());
        order.verify(scannerHttpApiIntake).observe(plan);
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
        ScannerHttpApiIntakeService.Plan plan = new ScannerHttpApiIntakeService.Plan(
                true, true, new com.enterprise.ai.capability.catalog.httpapi.HttpApiServiceScope(7L, "orders", "prod"),
                HttpApiSourceKind.OPENAPI_SCAN, List.of());
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectBlockerService.analyze(7L)).thenReturn(new ScanProjectBlockers(false, List.of(), List.of(), List.of()));
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of());
        when(scannerClient.scanOpenApi(any())).thenReturn(com.enterprise.ai.common.dto.ApiResult.ok(manifest));
        when(scannerHttpApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project, manifest.httpApis(), true)).thenReturn(plan);

        CapabilityScanProjectCatalogService.ScanResult result = service.rescan(7L);

        assertEquals(List.of("orders__find_order"), result.toolNames());
        ArgumentCaptor<CapabilityScannerClient.ScanRequest> request = ArgumentCaptor.forClass(CapabilityScannerClient.ScanRequest.class);
        verify(scannerClient).scanOpenApi(request.capture());
        assertNull(request.getValue().incrementalSinceEpochMs(),
                "OpenAPI rescans need a full document inventory before they may reconcile removed source bindings");
        verify(scannerClient, never()).scanController(any());
        InOrder order = org.mockito.Mockito.inOrder(scannerHttpApiIntake, scanProjectToolMapper);
        order.verify(scannerHttpApiIntake).prepare(HttpApiSourceKind.OPENAPI_SCAN, project, manifest.httpApis(), true);
        order.verify(scanProjectToolMapper).insert(any());
        order.verify(scannerHttpApiIntake).observe(plan);
    }

    @Test
    void singleToolControllerRefreshPreparesOwnerContractBeforeSourceWrites() {
        ScanProjectEntity project = project();
        project.setScanType("controller");
        project.setContextPath("/orders-api");
        ScanProjectToolEntity existing = tool(11L, "GET", "/orders/{id}", "Old description", "AI", null);
        existing.setProjectId(7L);
        existing.setName("find_order");
        existing.setContextPath("/orders-api");
        existing.setSourceLocation("OrdersController.java#OrdersController#find");
        CapabilityScannerClient.ToolData scannerTool = new CapabilityScannerClient.ToolData(
                "findOrder", "Fresh description", List.of(),
                new CapabilityScannerClient.ToolSourceData("controller", "OrdersController.java#OrdersController#find"),
                "GET", "/orders/{id}", null, "Order", Map.of());
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(existing);
        when(scannerClient.scanController(any())).thenReturn(okManifest(List.of(scannerTool)));
        var plan = singlePlan(project, HttpApiSourceKind.CONTROLLER_SCAN, "fixture");
        when(singleSourceIntake.prepare(HttpApiSourceKind.CONTROLLER_SCAN, project, existing, null)).thenReturn(plan);
        when(singleSourceIntake.observe(plan)).thenReturn(new ScannerHttpApiIntakeService.Summary(false, false, 0, 0));

        var result = service.rescanSingleTool(7L, 11L);
        ScanProjectToolEntity refreshed = result.sourceObservation();

        assertEquals(existing, refreshed);
        assertEquals("Fresh description", existing.getDescription());
        assertFalse(result.httpApiRefresh().confirmed());
        assertTrue(result.httpApiRefresh().fullSyncRequired());
        InOrder order = org.mockito.Mockito.inOrder(singleSourceIntake, scanProjectToolMapper);
        order.verify(singleSourceIntake).prepare(HttpApiSourceKind.CONTROLLER_SCAN, project, existing, null);
        order.verify(scanProjectToolMapper).updateById(existing);
        order.verify(singleSourceIntake).observe(plan);
    }

    @Test
    void singleToolOpenApiRefreshUsesSingleOwnerContractLifecycle() {
        ScanProjectEntity project = project();
        project.setScanType("openapi");
        project.setContextPath("/orders-api");
        ScanProjectToolEntity existing = tool(11L, "GET", "/orders/{id}", "Old description", "AI", null);
        existing.setProjectId(7L);
        existing.setName("find_order");
        existing.setContextPath("/orders-api");
        existing.setSourceLocation("orders.yaml#/paths/~1orders~1{id}/get");
        CapabilityScannerClient.ToolData scannerTool = new CapabilityScannerClient.ToolData(
                "findOrder", "Fresh description", List.of(),
                new CapabilityScannerClient.ToolSourceData("openapi", "orders.yaml#/paths/~1orders~1{id}/get"),
                "GET", "/orders/{id}", null, "Order", Map.of());
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(existing);
        when(scannerClient.scanOpenApi(any())).thenReturn(okManifest(List.of(scannerTool)));
        var plan = singlePlan(project, HttpApiSourceKind.OPENAPI_SCAN, "fixture");
        when(singleSourceIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project, existing, null)).thenReturn(plan);
        when(singleSourceIntake.observe(plan)).thenReturn(new ScannerHttpApiIntakeService.Summary(true, false, 1, 0));

        var result = service.rescanSingleTool(7L, 11L);
        ScanProjectToolEntity refreshed = result.sourceObservation();

        assertEquals(existing, refreshed);
        assertEquals("Fresh description", existing.getDescription());
        assertTrue(result.httpApiRefresh().confirmed());
        assertNull(result.httpApiRefresh().reason());
        InOrder order = org.mockito.Mockito.inOrder(singleSourceIntake, scanProjectToolMapper);
        order.verify(singleSourceIntake).prepare(HttpApiSourceKind.OPENAPI_SCAN, project, existing, null);
        order.verify(scanProjectToolMapper).updateById(existing);
        order.verify(singleSourceIntake).observe(plan);
    }

    @Test
    void changedRoutePreservesSourceEvidenceAndCommitsUnconfirmedRefreshResult() {
        ScanProjectEntity project = project();
        project.setScanType("controller"); project.setContextPath("/api");
        ScanProjectToolEntity existing = tool(11L, "GET", "/orders/{id}", "Old description", "AI", null);
        existing.setProjectId(7L); existing.setContextPath("/api");
        existing.setSourceLocation("OrdersController.java#OrdersController#find");
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(existing);
        var changed = new CapabilityScannerClient.ToolData("find", "new route", List.of(),
                new CapabilityScannerClient.ToolSourceData("controller", "OrdersController.java#OrdersController#find"),
                "GET", "/orders-v2/{id}", null, "Order", Map.of());
        when(scannerClient.scanController(any())).thenReturn(okManifest(List.of(changed)));
        var plan = singlePlan(project, HttpApiSourceKind.CONTROLLER_SCAN, "身份变化需完整同步");
        when(singleSourceIntake.prepare(HttpApiSourceKind.CONTROLLER_SCAN, project, existing, null)).thenReturn(plan);
        when(singleSourceIntake.observe(plan)).thenReturn(new ScannerHttpApiIntakeService.Summary(false, false, 0, 0));

        var result = service.rescanSingleTool(7L, 11L);

        assertFalse(result.httpApiRefresh().confirmed());
        assertEquals("身份变化需完整同步", result.httpApiRefresh().reason());
        assertEquals("/orders/{id}", result.sourceObservation().getEndpointPath());
        assertEquals("Old description", result.sourceObservation().getDescription());
        verify(singleSourceIntake).observe(plan);
        verify(scanProjectToolMapper, never()).updateById(existing);
    }

    @Test
    void scannerIoDoesNotParticipateInTheCommitTransaction() throws Exception {
        Transactional rescan = CapabilityScanProjectCatalogService.class.getMethod("rescan", Long.class)
                .getAnnotation(Transactional.class);
        Transactional single = CapabilityScanProjectCatalogService.class.getMethod("rescanSingleTool", Long.class, Long.class)
                .getAnnotation(Transactional.class);

        assertNotNull(rescan);
        assertEquals(org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED, rescan.propagation());
        assertEquals(org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED, single.propagation());
    }

    @Test
    void staleScanIsRejectedBeforeAnyIntakeAndScannerRunsOutsideBothShortTransactions() {
        var project = project();
        var revision = mock(CapabilityScanRevisionService.class);
        when(revision.revision(any())).thenReturn("before", "after");
        var manager = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        };
        service.setScanTransactions(manager, revision);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        org.mockito.Mockito.doAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return 7L;
        }).when(scanProjectMapper).lockCapabilityChanges(7L);
        when(scannerClient.scanOpenApi(any())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return okManifest(List.of());
        });
        assertThrows(CapabilityScanProjectCatalogService.ScanResultStaleException.class, () -> service.rescan(7L));
        verify(scanProjectMapper, org.mockito.Mockito.times(2)).lockCapabilityChanges(7L);
        verifyNoInteractions(scannerHttpApiIntake);
        verify(scanProjectMapper, never()).updateById(any(ScanProjectEntity.class));
        verify(scanProjectToolMapper, never()).insert(any(ScanProjectToolEntity.class));
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
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
        existing.setContextPath("/api");
        existing.setSourceLocation("orders.yaml#/paths/~1orders/post");
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(existing);
        when(scannerClient.scanOpenApi(any())).thenReturn(okManifest(List.of(
                new CapabilityScannerClient.ToolData(
                        "ignoredManifestName",
                        "Create order",
                        List.of(),
                        new CapabilityScannerClient.ToolSourceData("openapi", "orders.yaml#/paths/~1orders/post"),
                        "POST",
                        "/orders",
                        "OrderCreateRequest",
                        "OrderDTO",
                        Map.of()))));
        var plan = singlePlan(project, HttpApiSourceKind.OPENAPI_SCAN, "需完整同步");
        when(singleSourceIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project, existing, null)).thenReturn(plan);
        when(singleSourceIntake.observe(plan)).thenReturn(new ScannerHttpApiIntakeService.Summary(false, false, 0, 0));

        ScanProjectToolEntity result = service.rescanSingleTool(7L, 11L).sourceObservation();

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
        verifyNoInteractions(toolDefinitionMapper, scannerClient, scannerHttpApiIntake);
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
    void sourceListBatchesSharedMissingAndRemovedProjectionStatusesWithoutSingleRowQueries() {
        var linked = tool(11L, "GET", "/orders", "List orders", "AI", 99L);
        var shared = tool(12L, "GET", "/orders", "List orders", "AI", 99L);
        shared.setName(linked.getName()); shared.setTitle(linked.getTitle());
        shared.setDescription("different source contract");
        var missing = tool(13L, "GET", "/orders/{id}", "Find order", "AI", 100L);
        var removed = tool(14L, "DELETE", "/orders/{id}", "Delete order", "AI", 101L); removed.setRemovedFromSource(true);
        var unlinked = tool(15L, "POST", "/orders", "Create order", "AI", null);
        var global = globalTool(99L, linked);
        when(toolDefinitionMapper.selectBatchIds(List.of(99L, 100L, 101L))).thenReturn(List.of(global));
        var statuses = service.resolveToolLinks(List.of(linked, shared, missing, removed, unlinked));
        assertEquals(List.of("IN_SYNC", "PENDING_UPDATE", "GLOBAL_MISSING", "API_REMOVED_STALE", "NOT_LINKED"),
                statuses.values().stream().map(CapabilityScanProjectCatalogService.ToolLinkStatus::status).toList());
        assertEquals(List.of("description"), statuses.get(12L).diffFields());
        verify(toolDefinitionMapper).selectBatchIds(List.of(99L, 100L, 101L));
        verify(toolDefinitionMapper, never()).selectById(any(Long.class));
        verifyNoInteractions(scannerClient, scannerHttpApiIntake);
    }

    @Test
    void sourceModuleLabelsBatchOnlyCurrentProjectReferencesAndKeepSourceNameFallback() {
        var renamed = new com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity(); renamed.setId(4L); renamed.setProjectId(7L);
        renamed.setName("OrdersController"); renamed.setDisplayName("订单查询");
        var original = new com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity(); original.setId(5L); original.setProjectId(7L);
        original.setName("PaymentsController"); original.setDisplayName(" ");
        var foreign = new com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity(); foreign.setId(6L); foreign.setProjectId(8L); foreign.setName("ForeignController");
        when(scanModuleMapper.selectList(any())).thenReturn(List.of(renamed, original, foreign));
        assertEquals(Map.of(4L, "订单查询", 5L, "PaymentsController"), service.moduleDisplayNames(7L, List.of(4L, 5L, 6L, 4L)));
        verify(scanModuleMapper).selectList(any());
        verify(scanModuleMapper, never()).selectById(any(Long.class));
    }

    @Test
    void sourcesWithoutModuleReferencesSkipModuleReads() {
        assertTrue(service.moduleDisplayNames(7L, List.of()).isEmpty());
        verifyNoInteractions(scanModuleMapper);
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

        CapabilityScanProjectReadinessService.SdkAccessCheckResponse response = service.sdkAccessCheck(7L);

        CapabilityScanProjectReadinessService.SdkAccessCheckItem callback = response.checks().stream()
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

        CapabilityScanProjectReadinessService.SdkAccessCheckResponse response = service.sdkAccessCheck(7L);

        CapabilityScanProjectReadinessService.SdkAccessCheckItem callback = response.checks().stream()
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

        CapabilityScanProjectReadinessService.SdkAccessCheckResponse response = service.sdkAccessCheck(7L);

        CapabilityScanProjectReadinessService.SdkAccessCheckItem callback = response.checks().stream()
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
        verifyNoInteractions(semanticDocMapper, scanProjectToolMapper, scanModuleMapper, registryCredentialMapper, groupingService);
    }

    @Test
    void deletionRefusesUnavailableBlockersAndRetainsTheProject() {
        ScanProjectEntity project = new ScanProjectEntity(); project.setId(7L);
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        assertThrows(IllegalStateException.class, () -> service.delete(7L));
        verify(scanProjectMapper, never()).deleteById(any(Long.class));
        verifyNoInteractions(semanticDocMapper, scanProjectToolMapper, scanModuleMapper, registryCredentialMapper, groupingService);
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
        verify(groupingService).deleteProject(7L);
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

    private ScannerHttpApiSingleSourceIntakeService.Plan singlePlan(ScanProjectEntity project, HttpApiSourceKind kind, String reason) {
        return new ScannerHttpApiSingleSourceIntakeService.Plan(new ScannerHttpApiIntakeService.Plan(false, false,
                new HttpApiServiceScope(project.getId(), project.getProjectCode(), "default"), kind, List.of()), List.of(), reason);
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
