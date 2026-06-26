package com.enterprise.ai.agent.workflow.aicoding.pageassistant;

import com.enterprise.ai.agent.aicoding.AiCodingAccessGuard;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.platform.control.identity.PageActionRegistryEntity;
import com.enterprise.ai.agent.platform.control.identity.PageActionRegistryMapper;
import com.enterprise.ai.agent.platform.control.auth.AiCodingKeyContext;
import com.enterprise.ai.agent.platform.control.auth.PlatformAuthContext;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeGraphAdapter;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAccessDeniedException;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingAuthService;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingUnauthorizedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowPageAssistantAiCodingServiceTest {

    private static final String TEST_AI_CODING_KEY = "rac_test";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearAuth() {
        PlatformAuthContext.clear();
        AiCodingKeyContext.clear();
    }

    @Test
    void nonPageAssistantWorkflowRejected() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowPageAssistantAiCodingService service = newService(workflowService, mock(PageActionRegistryMapper.class));
        WorkflowDefinitionEntity workflow = sampleWorkflow();
        workflow.setWorkflowType("CHAT");
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getCatalog("wf-1"));
        assertTrue(error.getMessage().contains("PAGE_ASSISTANT"));
    }

    @Test
    void catalogReturnsActionSchemasAndMatchedNode() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
        WorkflowPageAssistantAiCodingService service = newService(workflowService, actionMapper);

        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(workflow));
        when(actionMapper.selectList(any())).thenReturn(List.of(activeCatalogAction()));

        WorkflowPageAssistantCatalogResponse response = service.getCatalog("wf-page");

        assertEquals("wf-page", response.getWorkflowId());
        assertEquals("orders.list", response.getPageKey());
        assertEquals(1, response.getCatalogActions().size());
        assertTrue(response.getCatalogActions().get(0).getInputSchema().get("required") instanceof List<?> required
                && required.contains("managerName"));
        assertEquals(1, response.getPageActionNodes().size());
        assertEquals(WorkflowPageAssistantCatalogResponse.MatchStatus.MATCHED,
                response.getPageActionNodes().get(0).getMatchStatus());
        assertEquals("search_result", response.getPageActionNodes().get(0).getOutputAlias());
    }

    @Test
    void catalogReadsNestedPageAssistantExtraFromAiCodingCreate() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
        WorkflowPageAssistantAiCodingService service = newService(workflowService, actionMapper);

        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        workflow.setExtraJson(objectMapper.writeValueAsString(Map.of(
                "pageAssistant", Map.of(
                        "pageKey", "orders.list",
                        "routePattern", "/orders",
                        "actionKeys", List.of("openDetail")
                )
        )));
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(workflow));
        when(actionMapper.selectList(any())).thenReturn(List.of(activeCatalogAction()));

        WorkflowPageAssistantCatalogResponse response = service.getCatalog("wf-page");

        assertEquals("orders.list", response.getPageKey());
        assertEquals("/orders", response.getRoutePattern());
        assertEquals(WorkflowPageAssistantCatalogResponse.MatchStatus.MATCHED,
                response.getPageActionNodes().get(0).getMatchStatus());
    }

    @Test
    void validateReportsMissingCatalog() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
        WorkflowPageAssistantAiCodingService service = newService(workflowService, actionMapper);

        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        workflow.setGraphSpecJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .entry("search")
                .node(pageActionNode("search", "orders.list", "missing.action", Map.of(), "result"))
                .build()));
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(workflow));
        when(actionMapper.selectList(any())).thenReturn(List.of(activeCatalogAction()));

        WorkflowPageAssistantValidateResponse response = service.validate("wf-page", null);

        assertFalse(response.isValid());
        assertEquals(WorkflowPageAssistantCatalogResponse.MatchStatus.MISSING,
                response.getItems().get(0).getMatchStatus());
        assertTrue(response.getItems().get(0).getErrors().stream()
                .anyMatch(item -> "CATALOG_MISSING".equals(item.getCode())));
    }

    @Test
    void validateReportsInactiveCatalog() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
        WorkflowPageAssistantAiCodingService service = newService(workflowService, actionMapper);

        PageActionRegistryEntity inactive = activeCatalogAction();
        inactive.setStatus("INACTIVE");
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(pageAssistantWorkflow()));
        when(actionMapper.selectList(any())).thenReturn(List.of(inactive));

        WorkflowPageAssistantValidateResponse response = service.validate("wf-page", null);

        assertFalse(response.isValid());
        assertEquals(WorkflowPageAssistantCatalogResponse.MatchStatus.INACTIVE,
                response.getItems().get(0).getMatchStatus());
    }

    @Test
    void validateReportsMissingRequiredArgs() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
        WorkflowPageAssistantAiCodingService service = newService(workflowService, actionMapper);

        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        workflow.setGraphSpecJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .entry("search")
                .node(pageActionNode("search", "orders.list", "openDetail", Map.of(), "result"))
                .build()));
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(workflow));
        when(actionMapper.selectList(any())).thenReturn(List.of(activeCatalogAction()));

        WorkflowPageAssistantValidateResponse response = service.validate("wf-page", null);

        assertFalse(response.isValid());
        assertTrue(response.getItems().get(0).getErrors().stream()
                .anyMatch(item -> "ARGS_REQUIRED_MISSING".equals(item.getCode())
                        && "managerName".equals(item.getField())));
    }

    @Test
    void validateReportsActionKeyEmpty() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
        WorkflowPageAssistantAiCodingService service = newService(workflowService, actionMapper);

        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        workflow.setGraphSpecJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .entry("search")
                .node(pageActionNode("search", "orders.list", "", Map.of("managerName", "x"), "result"))
                .build()));
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(workflow));
        when(actionMapper.selectList(any())).thenReturn(List.of());

        WorkflowPageAssistantValidateResponse response = service.validate("wf-page", null);

        assertFalse(response.isValid());
        assertEquals(WorkflowPageAssistantCatalogResponse.MatchStatus.ACTION_KEY_EMPTY,
                response.getItems().get(0).getMatchStatus());
    }

    @Test
    void smokeTestWithoutBridgeContextIsSkipped() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
        WorkflowPageAssistantAiCodingService service = newService(workflowService, actionMapper);

        when(workflowService.findById("wf-page")).thenReturn(Optional.of(pageAssistantWorkflow()));
        when(actionMapper.selectList(any())).thenReturn(List.of(activeCatalogAction()));

        WorkflowPageAssistantSmokeTestResponse response = service.smokeTest("wf-page",
                WorkflowPageAssistantSmokeTestRequest.builder()
                        .dryRun(false)
                        .runtimeContext(Map.of())
                        .build());

        assertEquals("SKIPPED", response.getStatus());
        assertFalse(response.isBridgeContextPresent());
        assertEquals("NONE", response.getRuntimeVerificationStatus());
    }

    @Test
    void smokeTestConfirmRequiredReturnsNeedConfirmWhenNotDryRun() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
        WorkflowPageAssistantAiCodingService service = newService(workflowService, actionMapper);

        PageActionRegistryEntity action = activeCatalogAction();
        action.setConfirmRequired(true);
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(pageAssistantWorkflow()));
        when(actionMapper.selectList(any())).thenReturn(List.of(action));

        WorkflowPageAssistantSmokeTestResponse response = service.smokeTest("wf-page",
                WorkflowPageAssistantSmokeTestRequest.builder()
                        .dryRun(false)
                        .runtimeContext(Map.of("embedSessionId", "embed-1"))
                        .build());

        assertEquals("NEED_CONFIRM", response.getStatus());
        assertEquals("NEED_CONFIRM", response.getNodes().get(0).getStatus());
    }

    @Test
    void smokeTestDryRunWarnsForConfirmRequiredWithoutExecuting() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
        WorkflowPageAssistantAiCodingService service = newService(workflowService, actionMapper);

        PageActionRegistryEntity action = activeCatalogAction();
        action.setConfirmRequired(true);
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(pageAssistantWorkflow()));
        when(actionMapper.selectList(any())).thenReturn(List.of(action));

        WorkflowPageAssistantSmokeTestResponse response = service.smokeTest("wf-page",
                WorkflowPageAssistantSmokeTestRequest.builder().build());

        assertEquals("DRY_RUN", response.getStatus());
        assertEquals("DRY_RUN", response.getNodes().get(0).getStatus());
        assertTrue(response.getWarnings().stream().anyMatch(item -> item.contains("confirmRequired")));
    }

    @Test
    void smokeTestAllowsRuntimePassOnlyWithVerificationEvidence() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
        WorkflowPageAssistantAiCodingService service = newService(workflowService, actionMapper);

        WorkflowDefinitionEntity workflow = pageAssistantWorkflow();
        workflow.setGraphSpecJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .entry("search")
                .node(pageActionNode("search", "orders.list", "openDetail",
                        Map.of("managerName", "Alice"), "result"))
                .build()));
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(workflow));
        when(actionMapper.selectList(any())).thenReturn(List.of(activeCatalogAction()));

        WorkflowPageAssistantSmokeTestResponse response = service.smokeTest("wf-page",
                WorkflowPageAssistantSmokeTestRequest.builder()
                        .dryRun(false)
                        .runtimeContext(Map.of("embedSessionId", "embed-1"))
                        .runtimeVerification(Map.of("browserRuntime", Map.of("status", "PASS")))
                        .build());

        assertEquals("RUNTIME_PASS", response.getStatus());
        assertEquals("PASS", response.getRuntimeVerificationStatus());
        assertEquals("RUNTIME_PASS", response.getNodes().get(0).getStatus());
    }

    @Test
    void missingAiCodingKeyIsUnauthorized() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowPageAssistantAiCodingService service = newService(workflowService, mock(PageActionRegistryMapper.class));
        AiCodingKeyContext.clear();
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(pageAssistantWorkflow()));

        assertThrows(WorkflowAiCodingUnauthorizedException.class, () -> service.getCatalog("wf-page"));
    }

    @Test
    void invalidAiCodingKeyIsForbidden() throws Exception {
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        when(scanProjectService.matchesAiCodingAccessKey(7L, "wrong-key")).thenReturn(false);
        WorkflowPageAssistantAiCodingService service = newService(
                workflowService, mock(PageActionRegistryMapper.class), scanProjectService);
        when(workflowService.findById("wf-page")).thenReturn(Optional.of(pageAssistantWorkflow()));
        AiCodingKeyContext.set("wrong-key");

        assertThrows(WorkflowAccessDeniedException.class, () -> service.getCatalog("wf-page"));
    }

    private WorkflowPageAssistantAiCodingService newService(WorkflowDefinitionService workflowService,
                                                            PageActionRegistryMapper actionMapper) {
        return newService(workflowService, actionMapper, null);
    }

    private WorkflowPageAssistantAiCodingService newService(WorkflowDefinitionService workflowService,
                                                            PageActionRegistryMapper actionMapper,
                                                            ScanProjectService scanProjectService) {
        AiCodingKeyContext.set(TEST_AI_CODING_KEY);
        ScanProjectService resolvedScanProjectService = scanProjectService == null
                ? mock(ScanProjectService.class)
                : scanProjectService;
        if (scanProjectService == null) {
            when(resolvedScanProjectService.matchesAiCodingAccessKey(7L, TEST_AI_CODING_KEY)).thenReturn(true);
            ScanProjectEntity project = new ScanProjectEntity();
            project.setId(7L);
            project.setProjectCode("orders");
            when(resolvedScanProjectService.getById(7L)).thenReturn(project);
        }
        return new WorkflowPageAssistantAiCodingService(
                workflowService,
                new WorkflowAiCodingAuthService(
                        resolvedScanProjectService,
                        new AiCodingAccessGuard(resolvedScanProjectService)),
                new WorkflowRuntimeGraphAdapter(objectMapper),
                actionMapper,
                mock(AgentWorkflowBindingService.class),
                objectMapper);
    }

    private WorkflowDefinitionEntity sampleWorkflow() {
        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setProjectId(7L);
        workflow.setProjectCode("orders");
        workflow.setKeySlug("orders-page");
        workflow.setName("Orders Page");
        workflow.setWorkflowType("CHAT");
        workflow.setRuntimeType("LANGGRAPH4J");
        workflow.setStatus("DRAFT");
        workflow.setUpdatedAt(LocalDateTime.parse("2026-06-16T10:00:00"));
        return workflow;
    }

    private WorkflowDefinitionEntity pageAssistantWorkflow() throws Exception {
        WorkflowDefinitionEntity workflow = sampleWorkflow();
        workflow.setId("wf-page");
        workflow.setWorkflowType("PAGE_ASSISTANT");
        workflow.setExtraJson(objectMapper.writeValueAsString(Map.of(
                "pageKey", "orders.list",
                "routePattern", "/orders",
                "actionKeys", List.of("openDetail")
        )));
        workflow.setGraphSpecJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .entry("search")
                .node(pageActionNode("search", "orders.list", "openDetail",
                        Map.of("managerName", "Alice"), "search_result"))
                .build()));
        return workflow;
    }

    private GraphSpec.Node pageActionNode(String id,
                                          String pageKey,
                                          String actionKey,
                                          Map<String, Object> args,
                                          String outputAlias) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("pageKey", pageKey);
        config.put("projectCode", "orders");
        config.put("actionKey", actionKey);
        config.put("args", args);
        config.put("outputAlias", outputAlias);
        return GraphSpec.Node.builder().id(id).type("PAGE_ACTION").config(config).build();
    }

    private PageActionRegistryEntity activeCatalogAction() throws Exception {
        PageActionRegistryEntity entity = new PageActionRegistryEntity();
        entity.setProjectCode("orders");
        entity.setPageKey("orders.list");
        entity.setActionKey("openDetail");
        entity.setTitle("Open Detail");
        entity.setDescription("Open order detail drawer");
        entity.setStatus("ACTIVE");
        entity.setConfirmRequired(false);
        entity.setInputSchemaJson(objectMapper.writeValueAsString(Map.of(
                "type", "object",
                "required", List.of("managerName"),
                "properties", Map.of("managerName", Map.of("type", "string"))
        )));
        entity.setOutputSchemaJson(objectMapper.writeValueAsString(Map.of("type", "object")));
        entity.setSampleArgsJson(objectMapper.writeValueAsString(Map.of("managerName", "Alice")));
        entity.setMetadataJson(objectMapper.writeValueAsString(Map.of("source", "test")));
        return entity;
    }
}
