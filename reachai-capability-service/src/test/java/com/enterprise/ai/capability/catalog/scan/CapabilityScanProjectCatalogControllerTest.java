package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectBlockers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityScanProjectCatalogControllerTest {

    @Test
    void keepsScanProjectCollectionRouteShapeOnCapabilityService() throws Exception {
        RequestMapping controllerMapping = CapabilityScanProjectCatalogController.class.getAnnotation(RequestMapping.class);
        Method create = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "create", CapabilityScanProjectCatalogController.ScanProjectUpsertRequest.class);
        Method list = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "list", String.class, String.class, String.class);
        Method get = CapabilityScanProjectCatalogController.class.getDeclaredMethod("get", Long.class);
        Method update = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "update", Long.class, CapabilityScanProjectCatalogController.ScanProjectUpsertRequest.class);
        Method updateAuthSettings = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "updateAuthSettings", Long.class, CapabilityScanProjectCatalogController.ScanProjectAuthSaveRequest.class);
        Method updateRegistryCredential = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "updateRegistryCredential", Long.class, CapabilityScanProjectCatalogController.ScanProjectRegistryCredentialSaveRequest.class);
        Method sdkAccessCheck = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "sdkAccessCheck", Long.class);
        Method updateScanSettings = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "updateScanSettings", Long.class, com.enterprise.ai.agent.capability.catalog.scan.ScanSettings.class);
        Method delete = CapabilityScanProjectCatalogController.class.getDeclaredMethod("delete", Long.class);
        Method scan = CapabilityScanProjectCatalogController.class.getDeclaredMethod("scan", Long.class);
        Method rescan = CapabilityScanProjectCatalogController.class.getDeclaredMethod("rescan", Long.class);
        Method triggerSdkScan = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "triggerSdkScan", Long.class);
        Method startSensitiveDataScan = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "startSensitiveDataScan", Long.class, String.class);
        Method sensitiveDataScanStatus = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "sensitiveDataScanStatus", Long.class, String.class);
        Method tools = CapabilityScanProjectCatalogController.class.getDeclaredMethod("tools", Long.class, String.class);
        Method tool = CapabilityScanProjectCatalogController.class.getDeclaredMethod("tool", Long.class, Long.class);
        Method rescanScanToolFromSource = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "rescanScanToolFromSource", Long.class, Long.class);
        Method reconcileTools = CapabilityScanProjectCatalogController.class.getDeclaredMethod("reconcileTools", Long.class);
        Method diffSummary = CapabilityScanProjectCatalogController.class.getDeclaredMethod("diffSummary", Long.class);
        Method operationBlockers = CapabilityScanProjectCatalogController.class.getDeclaredMethod("operationBlockers", Long.class, ScanProjectBlockers.Operation.class);

        assertArrayEquals(new String[] {"/api/scan-projects"}, controllerMapping.value());
        assertArrayEquals(new String[] {}, create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/{id}"}, get.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/{id}"}, update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/auth-settings"}, updateAuthSettings.getAnnotation(PatchMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/registry-credential"}, updateRegistryCredential.getAnnotation(PatchMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/sdk-access-check"}, sdkAccessCheck.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/scan-settings"}, updateScanSettings.getAnnotation(PatchMapping.class).value());
        assertArrayEquals(new String[] {"/{id}"}, delete.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/scan"}, scan.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/rescan"}, rescan.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/sdk-sync/scan"},
                triggerSdkScan.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/sensitive-data/scan"},
                startSensitiveDataScan.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/sensitive-data/status"},
                sensitiveDataScanStatus.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/tools"}, tools.getAnnotation(GetMapping.class).value());
        assertEquals(false, tools.getParameters()[1].getAnnotation(RequestParam.class).required());
        assertArrayEquals(new String[] {"/{projectId}/scan-tools/{scanToolId}"}, tool.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/{projectId}/scan-tools/{scanToolId}/rescan-from-source"},
                rescanScanToolFromSource.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{projectId}/tools/reconcile"}, reconcileTools.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/diff-summary"}, diffSummary.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/operation-blockers"}, operationBlockers.getAnnotation(GetMapping.class).value());
    }

    @Test
    void hasNoIndependentScanAssetMutationOrExecutionRoutes() {
        java.util.Set<String> retiredRoutes = java.util.Set.of(
                "PUT /{}/scan-tools/{}",
                "PUT /{}/scan-tools/{}/toggle",
                "POST /{}/scan-tools/{}/test",
                "POST /{}/scan-tools/{}/promote-to-tool",
                "POST /{}/scan-tools/{}/unpromote-from-global",
                "POST /{}/scan-tools/{}/push-to-global-tool",
                "POST /{}/scan-tools/promote-by-module");
        for (Method method : CapabilityScanProjectCatalogController.class.getDeclaredMethods()) {
            RequestMapping mapping = org.springframework.core.annotation.AnnotatedElementUtils
                    .findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) continue;
            for (String path : mapping.value()) {
                String normalized = path.replaceAll("\\{[^/{}]+\\}", "{}");
                for (var verb : mapping.method()) {
                    org.junit.jupiter.api.Assertions.assertFalse(retiredRoutes.contains(verb.name() + " " + normalized),
                            "retired source maintenance route reintroduced by " + method.getName());
                }
                org.junit.jupiter.api.Assertions.assertFalse(mapping.method().length == 0
                                && retiredRoutes.stream().anyMatch(route -> route.endsWith(" " + normalized)),
                        "retired source maintenance route reintroduced without a verb by " + method.getName());
            }
        }
    }

    @Test
    void listsScanProjectDtos() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectEntity project = project();
        when(service.list(null, null, null)).thenReturn(List.of(project));

        ResponseEntity<List<CapabilityScanProjectCatalogController.ScanProjectDTO>> response =
                controller.list(null, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CapabilityScanProjectCatalogController.ScanProjectDTO dto = response.getBody().get(0);
        assertEquals(7L, dto.id());
        assertEquals("Orders", dto.name());
        assertEquals("orders", dto.projectCode());
        assertEquals(4, dto.toolCount());
        assertEquals(4, dto.apiCount());
        assertEquals("none", dto.authType());
        assertNull(dto.registryCredentialConfigured());
        verify(service, never()).hasActiveRegistryCredential(anyString());
    }

    @Test
    void forwardsListQueryParamsToService() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectEntity project = project();
        when(service.list("班组", "REGISTERED", "scanned")).thenReturn(List.of(project));

        ResponseEntity<List<CapabilityScanProjectCatalogController.ScanProjectDTO>> response =
                controller.list("班组", "REGISTERED", "scanned");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verify(service).list("班组", "REGISTERED", "scanned");
    }

    @Test
    void getsScanProjectDetailById() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectEntity project = project();
        when(service.get(7L)).thenReturn(project);
        when(service.hasActiveRegistryCredential("orders")).thenReturn(true);

        ResponseEntity<CapabilityScanProjectCatalogController.ScanProjectDTO> response = controller.get(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(7L, response.getBody().id());
        assertEquals("orders", response.getBody().projectCode());
        assertEquals(Boolean.TRUE, response.getBody().registryCredentialConfigured());
        assertNull(response.getBody().registryAppKey());
        assertNull(response.getBody().registryAppSecret());
        verify(service).get(7L);
        verify(service).hasActiveRegistryCredential("orders");
    }

    @Test
    void triggerSdkScanDelegatesToSdkSyncTriggerService() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilitySdkSyncTriggerService sdkSyncTriggerService = mock(CapabilitySdkSyncTriggerService.class);
        CapabilityScanProjectCatalogController controller =
                new CapabilityScanProjectCatalogController(service, null, sdkSyncTriggerService);
        CapabilitySdkSyncTriggerService.SdkSyncTriggerResponse delegated =
                new CapabilitySdkSyncTriggerService.SdkSyncTriggerResponse(
                7L,
                "orders",
                "dev-1",
                "https://orders.example.com/reachai/registry/capabilities/sync",
                3,
                Map.of("capabilityCount", 3)
        );
        when(sdkSyncTriggerService.triggerScan(7L)).thenReturn(delegated);

        ResponseEntity<?> response = controller.triggerSdkScan(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(delegated, response.getBody());
        verify(sdkSyncTriggerService).triggerScan(7L);
    }

    @Test
    void triggerSdkScanReturnsActionableDownstreamDiagnostic() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilitySdkSyncTriggerService sdkSyncTriggerService = mock(CapabilitySdkSyncTriggerService.class);
        CapabilityScanProjectCatalogController controller =
                new CapabilityScanProjectCatalogController(service, null, sdkSyncTriggerService);
        String targetUrl = "https://orders.example.com/reachai/registry/capabilities/sync";
        when(sdkSyncTriggerService.triggerScan(7L)).thenThrow(
                new CapabilitySdkSyncTriggerService.SdkSyncRequestException(
                        "SDK_SYNC_AUTH_REJECTED",
                        targetUrl,
                        "业务系统返回 HTTP 403",
                        null));

        ResponseEntity<?> response = controller.triggerSdkScan(7L);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        CapabilityScanProjectCatalogController.SdkSyncErrorResponse body =
                (CapabilityScanProjectCatalogController.SdkSyncErrorResponse) response.getBody();
        assertEquals("SDK_SYNC_AUTH_REJECTED", body.code());
        assertEquals(targetUrl, body.targetUrl());
        assertEquals("业务系统返回 HTTP 403", body.message());
    }

    @Test
    void getReturnsNotFoundWhenProjectMissing() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        when(service.get(404L)).thenThrow(new IllegalArgumentException("missing"));

        ResponseEntity<CapabilityScanProjectCatalogController.ScanProjectDTO> response = controller.get(404L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void createDelegatesToService() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectEntity project = project();
        CapabilityScanProjectCatalogController.ScanProjectUpsertRequest request =
                new CapabilityScanProjectCatalogController.ScanProjectUpsertRequest(
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
                );
        when(service.create(request.toServiceRequest())).thenReturn(project);

        ResponseEntity<CapabilityScanProjectCatalogController.ScanProjectDTO> response = controller.create(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Orders", response.getBody().name());
        verify(service).create(request.toServiceRequest());
    }

    @Test
    void createReturnsBadRequestWhenValidationFails() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        CapabilityScanProjectCatalogController.ScanProjectUpsertRequest request =
                new CapabilityScanProjectCatalogController.ScanProjectUpsertRequest(
                        "",
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
                );
        when(service.create(request.toServiceRequest())).thenThrow(new IllegalArgumentException("name required"));

        ResponseEntity<CapabilityScanProjectCatalogController.ScanProjectDTO> response = controller.create(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void updateDelegatesToService() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectEntity project = project();
        CapabilityScanProjectCatalogController.ScanProjectUpsertRequest request =
                new CapabilityScanProjectCatalogController.ScanProjectUpsertRequest(
                        "Orders",
                        "orders",
                        "REGISTERED",
                        "dev",
                        "jsh",
                        "PRIVATE",
                        "https://api.example.com",
                        "",
                        "",
                        "auto",
                        null
                );
        when(service.update(7L, request.toServiceRequest())).thenReturn(project);

        ResponseEntity<CapabilityScanProjectCatalogController.ScanProjectDTO> response = controller.update(7L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Orders", response.getBody().name());
        verify(service).update(7L, request.toServiceRequest());
    }

    @Test
    void updateRegistryCredentialReturnsUpdatedProject() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectEntity project = project();
        CapabilityScanProjectCatalogController.ScanProjectRegistryCredentialSaveRequest request =
                new CapabilityScanProjectCatalogController.ScanProjectRegistryCredentialSaveRequest("app-orders", "secret");
        when(service.updateRegistryCredential(7L, request.toServiceRequest())).thenReturn(project);

        ResponseEntity<CapabilityScanProjectCatalogController.ScanProjectDTO> response =
                controller.updateRegistryCredential(7L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(service).updateRegistryCredential(7L, request.toServiceRequest());
    }

    @Test
    void sdkAccessCheckReturnsReadiness() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        CapabilityScanProjectCatalogService.SdkAccessCheckResponse check =
                new CapabilityScanProjectCatalogService.SdkAccessCheckResponse(
                        7L,
                        "orders",
                        "PASS",
                        List.of(new CapabilityScanProjectCatalogService.SdkAccessReadiness(
                                "CODE_READY", "代码接入", "PASS", "项目可读取")),
                        List.of(new CapabilityScanProjectCatalogService.SdkAccessCheckItem(
                                "PROJECT", "项目识别", "PASS", "已读取项目", null)));
        when(service.sdkAccessCheck(7L)).thenReturn(check);

        ResponseEntity<CapabilityScanProjectCatalogService.SdkAccessCheckResponse> response =
                controller.sdkAccessCheck(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PASS", response.getBody().overallStatus());
        verify(service).sdkAccessCheck(7L);
    }

    @Test
    void deleteDelegatesToService() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);

        ResponseEntity<?> response = controller.delete(7L);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).delete(7L);
    }

    @Test
    void deleteReturnsOwnerAssetBlockersForDirectCalls() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        var controller = new CapabilityScanProjectCatalogController(service);
        var blockers = new ScanProjectBlockers(true, List.of(), List.of(),
                List.of(new ScanProjectBlockers.AssetRef("BUSINESS_METHOD", 21L, "orders:read", "查询订单")));
        org.mockito.Mockito.doThrow(new CapabilityScanProjectCatalogService.ScanProjectBlockedException(blockers))
                .when(service).delete(7L);
        var response = controller.delete(7L);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(blockers, response.getBody());
    }

    @Test
    void scansProject() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        CapabilityScanProjectCatalogService.ScanResult result =
                new CapabilityScanProjectCatalogService.ScanResult(7L, "Orders", 1, List.of("orders__create"));
        when(service.scan(7L)).thenReturn(result);

        ResponseEntity<?> response = controller.scan(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CapabilityScanProjectCatalogController.ScanResultDTO body =
                (CapabilityScanProjectCatalogController.ScanResultDTO) response.getBody();
        assertEquals(1, body.toolCount());
        assertEquals(List.of("orders__create"), body.toolNames());
        verify(service).scan(7L);
    }

    @Test
    void scanReturnsBadRequestAndMarksFailedWhenScannerRejectsProject() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        when(service.scan(7L)).thenThrow(new IllegalArgumentException("scan path missing"));

        ResponseEntity<?> response = controller.scan(7L);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(service).markFailed(7L, "scan path missing");
    }

    @Test
    void rescansProject() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        CapabilityScanProjectCatalogService.ScanResult result =
                new CapabilityScanProjectCatalogService.ScanResult(7L, "Orders", 0, List.of());
        when(service.rescan(7L)).thenReturn(result);

        ResponseEntity<?> response = controller.rescan(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CapabilityScanProjectCatalogController.ScanResultDTO body =
                (CapabilityScanProjectCatalogController.ScanResultDTO) response.getBody();
        assertEquals(0, body.toolCount());
        verify(service).rescan(7L);
    }

    @Test
    void startsSensitiveDataScan() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilitySensitiveDataScanOrchestrator orchestrator = mock(CapabilitySensitiveDataScanOrchestrator.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service, orchestrator);
        when(orchestrator.startProjectScan(7L, "model-main")).thenReturn("task-1");

        ResponseEntity<?> response = controller.startSensitiveDataScan(7L, "model-main");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        CapabilityScanProjectCatalogController.SensitiveScanStartResponse body =
                (CapabilityScanProjectCatalogController.SensitiveScanStartResponse) response.getBody();
        assertEquals("task-1", body.taskId());
        verify(orchestrator).startProjectScan(7L, "model-main");
    }

    @Test
    void getsSensitiveDataScanStatus() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilitySensitiveDataScanOrchestrator orchestrator = mock(CapabilitySensitiveDataScanOrchestrator.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service, orchestrator);
        CapabilitySensitiveDataScanTask task = new CapabilitySensitiveDataScanTask();
        task.setTaskId("task-1");
        task.setProjectId(7L);
        task.setStage(CapabilitySensitiveDataScanTask.Stage.RUNNING);
        task.setTotalSteps(3);
        task.setCompletedSteps(1);
        when(orchestrator.getTask("task-1")).thenReturn(java.util.Optional.of(task));

        ResponseEntity<CapabilityScanProjectCatalogController.SensitiveScanTaskDTO> response =
                controller.sensitiveDataScanStatus(7L, "task-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("task-1", response.getBody().taskId());
        assertEquals("RUNNING", response.getBody().stage());
        assertEquals(3, response.getBody().totalSteps());
        assertEquals(1, response.getBody().completedSteps());
    }

    @Test
    void rescanReturnsConflictWhenProjectIsStillReferenced() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectBlockers blockers = new ScanProjectBlockers(
                true,
                List.of("orders_create"),
                List.of(), List.of());
        when(service.rescan(7L)).thenThrow(new CapabilityScanProjectCatalogService.ScanProjectBlockedException(blockers));

        ResponseEntity<?> response = controller.rescan(7L);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(blockers, response.getBody());
    }

    @Test
    void diffSummaryReturnsCatalogSummary() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        when(service.diffSummary(7L)).thenReturn(new CapabilityScanProjectCatalogService.ScanDiffSummary(
                7L,
                3,
                1,
                1,
                2,
                1,
                List.of(new CapabilityScanProjectCatalogService.DuplicateStableKey(
                        "GET /orders",
                        List.of(1L, 2L)))
        ));

        ResponseEntity<?> response = controller.diffSummary(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CapabilityScanProjectCatalogController.ScanDiffSummaryDTO body =
                (CapabilityScanProjectCatalogController.ScanDiffSummaryDTO) response.getBody();
        assertEquals(3, body.toolCount());
        assertEquals(1, body.duplicates().size());
        assertEquals("GET /orders", body.duplicates().get(0).stableKey());
    }

    @Test
    void listsScanProjectTools() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectToolEntity tool = scanTool();
        when(service.listTools(7L)).thenReturn(List.of(tool));
        when(service.resolveToolLink(tool)).thenReturn(new CapabilityScanProjectCatalogService.ToolLinkStatus(
                "PENDING_UPDATE",
                "Global Tool differs from scan row",
                List.of("description")));

        ResponseEntity<List<CapabilityScanProjectCatalogController.ProjectToolDTO>> response =
                controller.tools(7L, "summary");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CapabilityScanProjectCatalogController.ProjectToolDTO dto = response.getBody().get(0);
        assertEquals(11L, dto.scanToolId());
        assertEquals(7L, dto.projectId());
        assertEquals("orders_create", dto.name());
        assertEquals(1, dto.parameterCount());
        assertEquals("PENDING_UPDATE", dto.toolLinkStatus());
        assertEquals(true, dto.globalToolOutOfSync());
        assertEquals(List.of("description"), dto.toolSyncDiffFields());
        assertEquals(99L, dto.globalToolDefinitionId());
        verify(service).listTools(7L);
    }

    @Test
    void toolsReturnsNotFoundWhenProjectMissing() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        when(service.listTools(404L)).thenThrow(new IllegalArgumentException("missing"));

        ResponseEntity<List<CapabilityScanProjectCatalogController.ProjectToolDTO>> response =
                controller.tools(404L, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getsScanProjectToolDetail() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectToolEntity tool = scanTool();
        when(service.getTool(7L, 11L)).thenReturn(tool);

        ResponseEntity<CapabilityScanProjectCatalogController.ProjectToolDTO> response =
                controller.tool(7L, 11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CapabilityScanProjectCatalogController.ProjectToolDTO dto = response.getBody();
        assertEquals(11L, dto.scanToolId());
        assertEquals("orders_create", dto.name());
        assertEquals(1, dto.parameters().size());
        assertEquals("body", dto.parameters().get(0).name());
        assertEquals("LINKED", dto.toolLinkStatus());
        verify(service).getTool(7L, 11L);
    }

    @Test
    void rescansSingleScanToolFromSource() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectToolEntity tool = scanTool();
        tool.setDescription("Fresh description");
        when(service.rescanSingleTool(7L, 11L)).thenReturn(tool);

        ResponseEntity<?> response = controller.rescanScanToolFromSource(7L, 11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CapabilityScanProjectCatalogController.ProjectToolDTO body =
                (CapabilityScanProjectCatalogController.ProjectToolDTO) response.getBody();
        assertEquals("Fresh description", body.description());
        verify(service).rescanSingleTool(7L, 11L);
    }

    @Test
    void toolDetailReturnsNotFoundWhenMissing() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        when(service.getTool(7L, 404L)).thenThrow(new IllegalArgumentException("missing"));

        ResponseEntity<CapabilityScanProjectCatalogController.ProjectToolDTO> response =
                controller.tool(7L, 404L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }






    @Test
    void reconcilesScanProjectTools() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        CapabilityScanProjectCatalogService.ToolReconcileSummary summary =
                new CapabilityScanProjectCatalogService.ToolReconcileSummary(0, 1, 2, 3, 4, 5, 0);
        when(service.reconcileTools(7L)).thenReturn(summary);

        ResponseEntity<CapabilityScanProjectCatalogService.ToolReconcileSummary> response =
                controller.reconcileTools(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3, response.getBody().pendingUpdate());
        assertEquals(5, response.getBody().globalMissing());
        verify(service).reconcileTools(7L);
    }





    @Test
    void diffSummaryReturnsNotFoundWhenProjectMissing() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        when(service.diffSummary(404L)).thenThrow(new IllegalArgumentException("missing"));

        ResponseEntity<?> response = controller.diffSummary(404L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void operationBlockersReturnsCatalogBlockers() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectBlockers blockers = new ScanProjectBlockers(
                true,
                List.of("orders_create"),
                List.of(new ScanProjectBlockers.AgentRef("agent-1", "Team Assistant")), List.of());
        when(service.operationBlockers(7L, ScanProjectBlockers.Operation.RESCAN)).thenReturn(blockers);

        ResponseEntity<?> response = controller.operationBlockers(7L, ScanProjectBlockers.Operation.RESCAN);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(blockers, response.getBody());
    }

    @Test
    void operationBlockersReturnsNotFoundWhenProjectMissing() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        when(service.operationBlockers(404L, ScanProjectBlockers.Operation.RESCAN)).thenThrow(new IllegalArgumentException("missing"));

        ResponseEntity<?> response = controller.operationBlockers(404L, ScanProjectBlockers.Operation.RESCAN);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    private ScanProjectEntity project() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setName("Orders");
        project.setProjectCode("orders");
        project.setProjectKind("SCAN");
        project.setEnvironment("default");
        project.setVisibility("PRIVATE");
        project.setBaseUrl("https://api.example.com");
        project.setContextPath("");
        project.setScanPath("/openapi.json");
        project.setScanType("openapi");
        project.setToolCount(4);
        project.setStatus("created");
        project.setAuthType(null);
        return project;
    }

    private ScanProjectToolEntity scanTool() {
        ScanProjectToolEntity tool = new ScanProjectToolEntity();
        tool.setId(11L);
        tool.setProjectId(7L);
        tool.setModuleId(3L);
        tool.setName("orders_create");
        tool.setTitle("创建订单");
        tool.setDescription("Create order");
        tool.setParametersJson("[{\"name\":\"body\",\"type\":\"object\",\"description\":\"request\",\"required\":true,\"location\":\"body\"}]");
        tool.setSource("code");
        tool.setSourceLocation("com.example.OrderController#create");
        tool.setHttpMethod("POST");
        tool.setBaseUrl("https://api.example.com");
        tool.setContextPath("/api");
        tool.setEndpointPath("/orders");
        tool.setRequestBodyType("OrderCreateRequest");
        tool.setResponseType("OrderDTO");
        tool.setAiDescription("AI description");
        tool.setCapabilityMetadataJson("{\"group\":\"order\"}");
        tool.setEnabled(true);
        tool.setGlobalToolDefinitionId(99L);
        tool.setRemovedFromSource(false);
        return tool;
    }
}
