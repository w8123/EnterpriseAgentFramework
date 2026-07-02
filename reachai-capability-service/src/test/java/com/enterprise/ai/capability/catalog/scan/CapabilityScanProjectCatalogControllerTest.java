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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityScanProjectCatalogControllerTest {

    @Test
    void keepsScanProjectCollectionRouteShapeOnCapabilityService() throws Exception {
        RequestMapping controllerMapping = CapabilityScanProjectCatalogController.class.getAnnotation(RequestMapping.class);
        Method create = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "create", CapabilityScanProjectCatalogController.ScanProjectUpsertRequest.class);
        Method list = CapabilityScanProjectCatalogController.class.getDeclaredMethod("list");
        Method get = CapabilityScanProjectCatalogController.class.getDeclaredMethod("get", Long.class);
        Method update = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "update", Long.class, CapabilityScanProjectCatalogController.ScanProjectUpsertRequest.class);
        Method updateAuthSettings = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "updateAuthSettings", Long.class, CapabilityScanProjectCatalogController.ScanProjectAuthSaveRequest.class);
        Method updateRegistryCredential = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "updateRegistryCredential", Long.class, CapabilityScanProjectCatalogController.ScanProjectRegistryCredentialSaveRequest.class);
        Method sdkAccessCheck = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "sdkAccessCheck", Long.class, CapabilityScanProjectCatalogController.SdkAccessCheckRequest.class);
        Method updateScanSettings = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "updateScanSettings", Long.class, com.enterprise.ai.agent.capability.catalog.scan.ScanSettings.class);
        Method delete = CapabilityScanProjectCatalogController.class.getDeclaredMethod("delete", Long.class);
        Method scan = CapabilityScanProjectCatalogController.class.getDeclaredMethod("scan", Long.class);
        Method rescan = CapabilityScanProjectCatalogController.class.getDeclaredMethod("rescan", Long.class);
        Method startSensitiveDataScan = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "startSensitiveDataScan", Long.class, String.class);
        Method sensitiveDataScanStatus = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "sensitiveDataScanStatus", Long.class, String.class);
        Method tools = CapabilityScanProjectCatalogController.class.getDeclaredMethod("tools", Long.class, String.class);
        Method tool = CapabilityScanProjectCatalogController.class.getDeclaredMethod("tool", Long.class, Long.class);
        Method rescanScanToolFromSource = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "rescanScanToolFromSource", Long.class, Long.class);
        Method updateTool = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "updateTool", Long.class, Long.class, CapabilityScanProjectCatalogController.ScanProjectToolUpsertRequest.class);
        Method toggleTool = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "toggleTool", Long.class, Long.class, CapabilityScanProjectCatalogController.ScanProjectToolToggleRequest.class);
        Method testTool = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "testTool", Long.class, Long.class, CapabilityScanProjectCatalogController.ToolTestRequest.class);
        Method reconcileTools = CapabilityScanProjectCatalogController.class.getDeclaredMethod("reconcileTools", Long.class);
        Method promoteTool = CapabilityScanProjectCatalogController.class.getDeclaredMethod("promoteTool", Long.class, Long.class);
        Method unpromoteTool = CapabilityScanProjectCatalogController.class.getDeclaredMethod("unpromoteTool", Long.class, Long.class);
        Method pushToolToGlobal = CapabilityScanProjectCatalogController.class.getDeclaredMethod("pushToolToGlobal", Long.class, Long.class);
        Method promoteModuleTools = CapabilityScanProjectCatalogController.class.getDeclaredMethod(
                "promoteModuleTools", Long.class, CapabilityScanProjectCatalogController.PromoteModuleToolsRequest.class);
        Method diffSummary = CapabilityScanProjectCatalogController.class.getDeclaredMethod("diffSummary", Long.class);
        Method operationBlockers = CapabilityScanProjectCatalogController.class.getDeclaredMethod("operationBlockers", Long.class);

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
        assertArrayEquals(new String[] {"/{id}/sensitive-data/scan"},
                startSensitiveDataScan.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/sensitive-data/status"},
                sensitiveDataScanStatus.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/tools"}, tools.getAnnotation(GetMapping.class).value());
        assertEquals(false, tools.getParameters()[1].getAnnotation(RequestParam.class).required());
        assertArrayEquals(new String[] {"/{projectId}/scan-tools/{scanToolId}"}, tool.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/{projectId}/scan-tools/{scanToolId}/rescan-from-source"},
                rescanScanToolFromSource.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{projectId}/scan-tools/{scanToolId}"}, updateTool.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/{projectId}/scan-tools/{scanToolId}/toggle"}, toggleTool.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/{projectId}/scan-tools/{scanToolId}/test"}, testTool.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{projectId}/tools/reconcile"}, reconcileTools.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{projectId}/scan-tools/{scanToolId}/promote-to-tool"}, promoteTool.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{projectId}/scan-tools/{scanToolId}/unpromote-from-global"}, unpromoteTool.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{projectId}/scan-tools/{scanToolId}/push-to-global-tool"}, pushToolToGlobal.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{projectId}/scan-tools/promote-by-module"}, promoteModuleTools.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/diff-summary"}, diffSummary.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/operation-blockers"}, operationBlockers.getAnnotation(GetMapping.class).value());
    }

    @Test
    void listsScanProjectDtos() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectEntity project = project();
        when(service.list()).thenReturn(List.of(project));

        ResponseEntity<List<CapabilityScanProjectCatalogController.ScanProjectDTO>> response = controller.list();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CapabilityScanProjectCatalogController.ScanProjectDTO dto = response.getBody().get(0);
        assertEquals(7L, dto.id());
        assertEquals("Orders", dto.name());
        assertEquals("orders", dto.projectCode());
        assertEquals(4, dto.toolCount());
        assertEquals(4, dto.apiCount());
        assertEquals("none", dto.authType());
    }

    @Test
    void getsScanProjectDetailById() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectEntity project = project();
        when(service.get(7L)).thenReturn(project);

        ResponseEntity<CapabilityScanProjectCatalogController.ScanProjectDTO> response = controller.get(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(7L, response.getBody().id());
        assertEquals("orders", response.getBody().projectCode());
        verify(service).get(7L);
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
                controller.sdkAccessCheck(7L, new CapabilityScanProjectCatalogController.SdkAccessCheckRequest(null, null, null, null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PASS", response.getBody().overallStatus());
        verify(service).sdkAccessCheck(7L);
    }

    @Test
    void deleteDelegatesToService() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);

        ResponseEntity<Void> response = controller.delete(7L);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).delete(7L);
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
                List.of(),
                List.of());
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
    void updatesScanProjectTool() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectToolEntity tool = scanTool();
        CapabilityScanProjectCatalogController.ScanProjectToolUpsertRequest request =
                new CapabilityScanProjectCatalogController.ScanProjectToolUpsertRequest(
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
                );
        when(service.updateTool(7L, 11L, request.toServiceRequest())).thenReturn(tool);

        ResponseEntity<CapabilityScanProjectCatalogController.ProjectToolDTO> response =
                controller.updateTool(7L, 11L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("orders_create", response.getBody().name());
        verify(service).updateTool(7L, 11L, request.toServiceRequest());
    }

    @Test
    void updateToolReturnsBadRequestWhenValidationFails() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        CapabilityScanProjectCatalogController.ScanProjectToolUpsertRequest request =
                new CapabilityScanProjectCatalogController.ScanProjectToolUpsertRequest(
                        "",
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
                );
        when(service.updateTool(7L, 11L, request.toServiceRequest())).thenThrow(new IllegalArgumentException("name required"));

        ResponseEntity<CapabilityScanProjectCatalogController.ProjectToolDTO> response =
                controller.updateTool(7L, 11L, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void togglesScanProjectTool() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectToolEntity tool = scanTool();
        tool.setEnabled(false);
        CapabilityScanProjectCatalogController.ScanProjectToolToggleRequest request =
                new CapabilityScanProjectCatalogController.ScanProjectToolToggleRequest(false);
        when(service.toggleTool(7L, 11L, false)).thenReturn(tool);

        ResponseEntity<CapabilityScanProjectCatalogController.ProjectToolDTO> response =
                controller.toggleTool(7L, 11L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().enabled());
        verify(service).toggleTool(7L, 11L, false);
    }

    @Test
    void testsScanProjectTool() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        CapabilityScanProjectCatalogController.ToolTestRequest request =
                new CapabilityScanProjectCatalogController.ToolTestRequest(Map.of("orderNo", "A001"));
        when(service.testTool(7L, 11L, request.args()))
                .thenReturn(Map.of("success", true, "data", Map.of("status", "CREATED")));

        ResponseEntity<CapabilityScanProjectCatalogController.ToolTestResult> response =
                controller.testTool(7L, 11L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().success());
        assertEquals("{status=CREATED}", response.getBody().result());
        verify(service).testTool(7L, 11L, request.args());
    }

    @Test
    void testScanProjectToolReturnsFailurePayload() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        CapabilityScanProjectCatalogController.ToolTestRequest request =
                new CapabilityScanProjectCatalogController.ToolTestRequest(Map.of());
        when(service.testTool(7L, 404L, request.args()))
                .thenThrow(new IllegalArgumentException("Scan project tool does not exist: 404"));

        ResponseEntity<CapabilityScanProjectCatalogController.ToolTestResult> response =
                controller.testTool(7L, 404L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().success());
        assertEquals("Scan project tool does not exist: 404", response.getBody().errorMessage());
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
    void promotesScanProjectTool() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        CapabilityScanProjectCatalogService.PromotedGlobalTool promoted =
                new CapabilityScanProjectCatalogService.PromotedGlobalTool(501L, "orders_create");
        when(service.promoteTool(7L, 11L)).thenReturn(promoted);

        ResponseEntity<CapabilityScanProjectCatalogService.PromotedGlobalTool> response =
                controller.promoteTool(7L, 11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(501L, response.getBody().globalToolId());
        assertEquals("orders_create", response.getBody().globalToolName());
        verify(service).promoteTool(7L, 11L);
    }

    @Test
    void unpromotesScanProjectTool() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectToolEntity tool = scanTool();
        tool.setGlobalToolDefinitionId(null);
        when(service.unpromoteTool(7L, 11L)).thenReturn(tool);

        ResponseEntity<CapabilityScanProjectCatalogController.ProjectToolDTO> response =
                controller.unpromoteTool(7L, 11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(null, response.getBody().globalToolDefinitionId());
        assertEquals("NOT_LINKED", response.getBody().toolLinkStatus());
        verify(service).unpromoteTool(7L, 11L);
    }

    @Test
    void pushesScanProjectToolToGlobalTool() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        ScanProjectToolEntity tool = scanTool();
        when(service.pushToolToGlobal(7L, 11L)).thenReturn(tool);
        when(service.resolveToolLink(tool)).thenReturn(new CapabilityScanProjectCatalogService.ToolLinkStatus(
                "IN_SYNC",
                null,
                List.of()));

        ResponseEntity<CapabilityScanProjectCatalogController.ProjectToolDTO> response =
                controller.pushToolToGlobal(7L, 11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("IN_SYNC", response.getBody().toolLinkStatus());
        assertEquals(false, response.getBody().globalToolOutOfSync());
        verify(service).pushToolToGlobal(7L, 11L);
    }

    @Test
    void promotesModuleScanTools() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        CapabilityScanProjectCatalogController.PromoteModuleToolsRequest request =
                new CapabilityScanProjectCatalogController.PromoteModuleToolsRequest(3L);
        CapabilityScanProjectCatalogService.BatchPromoteToToolsResult promoted =
                new CapabilityScanProjectCatalogService.BatchPromoteToToolsResult(
                        1,
                        List.of(new CapabilityScanProjectCatalogService.PromotedGlobalTool(501L, "orders_create")));
        when(service.promoteModuleTools(7L, 3L)).thenReturn(promoted);

        ResponseEntity<CapabilityScanProjectCatalogService.BatchPromoteToToolsResult> response =
                controller.promoteModuleTools(7L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().promotedCount());
        assertEquals("orders_create", response.getBody().items().get(0).globalToolName());
        verify(service).promoteModuleTools(7L, 3L);
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
                List.of(),
                List.of(new ScanProjectBlockers.AgentRef("agent-1", "Team Assistant")));
        when(service.operationBlockers(7L)).thenReturn(blockers);

        ResponseEntity<?> response = controller.operationBlockers(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(blockers, response.getBody());
    }

    @Test
    void operationBlockersReturnsNotFoundWhenProjectMissing() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityScanProjectCatalogController controller = new CapabilityScanProjectCatalogController(service);
        when(service.operationBlockers(404L)).thenThrow(new IllegalArgumentException("missing"));

        ResponseEntity<?> response = controller.operationBlockers(404L);

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
        tool.setAgentVisible(true);
        tool.setLightweightEnabled(false);
        tool.setGlobalToolDefinitionId(99L);
        tool.setRemovedFromSource(false);
        return tool;
    }
}
