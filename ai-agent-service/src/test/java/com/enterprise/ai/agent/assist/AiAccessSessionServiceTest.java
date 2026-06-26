package com.enterprise.ai.agent.assist;

import com.enterprise.ai.agent.registry.SdkAccessCheckService;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import com.enterprise.ai.agent.platform.control.identity.PageActionRegistryEntity;
import com.enterprise.ai.agent.platform.control.identity.PageActionRegistryMapper;
import com.enterprise.ai.agent.platform.control.identity.PageActionCatalogContracts;
import com.enterprise.ai.agent.platform.control.identity.PageRegistryEntity;
import com.enterprise.ai.agent.platform.control.identity.PageRegistryMapper;
import com.enterprise.ai.agent.platform.control.identity.PageCatalogRegisterResult;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAccessSessionServiceTest {

    @Mock
    private ScanProjectService scanProjectService;
    @Mock
    private AiAccessSessionMapper sessionMapper;
    @Mock
    private AiAccessStepMapper stepMapper;
    @Mock
    private SdkAccessCheckService sdkAccessCheckService;
    @Mock
    private PageRegistryMapper pageRegistryMapper;
    @Mock
    private PageActionRegistryMapper pageActionRegistryMapper;
    @Mock
    private WorkflowDefinitionService workflowDefinitionService;
    @InjectMocks
    private AiAccessSessionService service;

    @Test
    void getOrCreateLatestCreatesStandardStepsForProject() {
        when(scanProjectService.getById(1L)).thenReturn(project());
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        AiAccessSessionService.AccessSessionView view = service.getOrCreateLatest(1L, "Cursor");

        assertNotNull(view.sessionId());
        assertEquals("OPEN", view.status());
        assertEquals(10, view.steps().size());
        assertTrue(view.steps().stream().anyMatch(step -> "gateway-whitelist".equals(step.stepKey())));
        assertTrue(view.steps().stream().anyMatch(step -> "connectivity-check".equals(step.stepKey())));
        verify(sessionMapper).insert(any(AiAccessSessionEntity.class));
        ArgumentCaptor<AiAccessStepEntity> stepCaptor = ArgumentCaptor.forClass(AiAccessStepEntity.class);
        verify(stepMapper, org.mockito.Mockito.times(10)).insert(stepCaptor.capture());
        assertTrue(stepCaptor.getAllValues().stream()
                .allMatch(step -> view.sessionId().equals(step.getSessionId())));
    }

    @Test
    void getOrCreatePageAssistantLatestCreatesPageAssistantStepsAndTargetMetadata() {
        when(scanProjectService.getById(1L)).thenReturn(project());
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        AiAccessSessionService.AccessSessionView view = service.getOrCreatePageAssistantLatest(
                1L,
                new AiAccessSessionService.PageAssistantSessionRequest(
                        "Cursor",
                        "teamArchive.list",
                        "/teams/archive",
                        List.of("getPageState", "search", "readTable")));

        assertNotNull(view.sessionId());
        assertEquals("PAGE_ASSISTANT", view.scenario());
        assertEquals("teamArchive.list", view.targetPageKey());
        assertEquals("/teams/archive", view.targetRoute());
        assertEquals(8, view.steps().size());
        assertTrue(view.steps().stream().anyMatch(step -> "page-manifest".equals(step.stepKey())));
        assertTrue(view.steps().stream().anyMatch(step -> "frontend-handler".equals(step.stepKey())));
        assertTrue(view.steps().stream().anyMatch(step -> "browser-verify".equals(step.stepKey())));
        ArgumentCaptor<AiAccessSessionEntity> sessionCaptor = ArgumentCaptor.forClass(AiAccessSessionEntity.class);
        verify(sessionMapper).insert(sessionCaptor.capture());
        assertEquals("PAGE_ASSISTANT", sessionCaptor.getValue().getScenario());
        assertEquals("teamArchive.list", sessionCaptor.getValue().getTargetPageKey());
        assertEquals("/teams/archive", sessionCaptor.getValue().getTargetRoute());
        assertTrue(sessionCaptor.getValue().getMetadataJson().contains("getPageState"));
        verify(stepMapper, org.mockito.Mockito.times(8)).insert(any(AiAccessStepEntity.class));
    }

    @Test
    void listPageAssistantSessionsReturnsCardSummariesForProjectPages() {
        AiAccessSessionEntity teamArchive = session("session-team");
        teamArchive.setScenario("PAGE_ASSISTANT");
        teamArchive.setTargetPageKey("teamArchive.list");
        teamArchive.setTargetRoute("/team-build/depart-management");
        teamArchive.setStatus("RUNNING");
        teamArchive.setMetadataJson("{\"actionKeys\":[\"getPageState\",\"search\",\"readTable\"]}");
        teamArchive.setUpdatedAt(LocalDateTime.of(2026, 6, 12, 10, 30));
        AiAccessSessionEntity cycle = session("session-cycle");
        cycle.setScenario("PAGE_ASSISTANT");
        cycle.setTargetPageKey("cycle.list");
        cycle.setTargetRoute("/team-build/cycle");
        cycle.setStatus("PASS");
        cycle.setMetadataJson("{\"actionKeys\":[\"getPageState\",\"readTable\"]}");
        cycle.setUpdatedAt(LocalDateTime.of(2026, 6, 12, 11, 0));
        when(sessionMapper.selectList(any())).thenReturn(List.of(teamArchive, cycle));
        when(stepMapper.selectList(any()))
                .thenReturn(List.of(
                        step("session-team", "page-manifest", "PASS"),
                        step("session-team", "page-registry", "WARN"),
                        step("session-team", "browser-verify", "TODO")))
                .thenReturn(List.of(
                        step("session-cycle", "page-manifest", "PASS"),
                        step("session-cycle", "page-registry", "PASS"),
                        step("session-cycle", "browser-verify", "PASS")));

        List<AiAccessSessionService.PageAssistantSessionSummaryView> summaries =
                service.listPageAssistantSessions(1L, null);

        assertEquals(2, summaries.size());
        assertEquals("teamArchive.list", summaries.get(0).targetPageKey());
        assertEquals("IN_PROGRESS", summaries.get(0).completionState());
        assertEquals(3, summaries.get(0).actionCount());
        assertEquals("COMPLETED", summaries.get(1).completionState());
        assertEquals(2, summaries.get(1).actionCount());
    }

    @Test
    void reportStepUpdatesStepAndRecomputesProgress() {
        AiAccessSessionEntity session = session("session-1");
        AiAccessStepEntity manifest = step("session-1", "project-manifest", "PASS");
        AiAccessStepEntity gateway = step("session-1", "gateway-whitelist", "TODO");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(stepMapper.selectList(any())).thenReturn(List.of(manifest, gateway));

        AiAccessSessionService.AccessSessionView view = service.reportStep(
                1L,
                "session-1",
                "gateway-whitelist",
                new AiAccessSessionService.StepReportRequest(
                        "PASS",
                        "Gateway whitelist has been configured.",
                        List.of("qmssmp-gateway/src/main/java/SecurityConfiguration.java"),
                        Map.of("command", "mvn -DskipTests compile", "exitCode", 0),
                        "cursor"));

        assertEquals("RUNNING", view.status());
        assertEquals(10, view.totalSteps());
        assertEquals(2, view.completedSteps());
        verify(stepMapper).updateById(any(AiAccessStepEntity.class));
        verify(sessionMapper).updateById(any(AiAccessSessionEntity.class));
    }

    @Test
    void reportWorkflowAiCodingResultStoresDraftStepEvidence() {
        AiAccessSessionEntity session = session("session-page");
        session.setScenario("PAGE_ASSISTANT");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step("session-page", "page-manifest", "TODO"),
                step("session-page", "route-detection", "TODO"),
                step("session-page", "page-structure", "TODO"),
                step("session-page", "action-design", "TODO"),
                step("session-page", "frontend-handler", "TODO"),
                step("session-page", "page-registry", "TODO"),
                step("session-page", "browser-verify", "TODO"),
                step("session-page", "handoff-summary", "TODO")
        ));

        Map<String, Object> validation = Map.of(
                "overallStatus", "PASS",
                "errors", List.of(),
                "warnings", List.of());
        Map<String, Object> pageAssistantValidation = Map.of(
                "overallStatus", "WARN",
                "matchedActions", List.of("search"),
                "missingActions", List.of(),
                "warnings", List.of("placeholder model"));
        Map<String, Object> runtimeVerification = Map.of(
                "browserRuntime", Map.of(
                        "status", "WARN",
                        "message", "business frontend not reachable",
                        "checkedActions", List.of("search")));

        AiAccessSessionService.AccessSessionView view = service.reportWorkflowAiCodingResult(
                1L,
                "session-page",
                new AiAccessSessionService.WorkflowAiCodingResultRequest(
                        "wf-123",
                        "demo-page-assistant",
                        "Demo Page Assistant",
                        "WARN",
                        "Workflow draft created via AI Coding",
                        validation,
                        pageAssistantValidation,
                        runtimeVerification,
                        "/workflows/wf-123/studio"));

        AiAccessSessionService.AccessStepView draftStep = view.steps().stream()
                .filter(step -> AiAccessSessionService.WORKFLOW_AI_CODING_DRAFT_STEP_KEY.equals(step.stepKey()))
                .findFirst()
                .orElseThrow();
        assertEquals("WARN", draftStep.status());
        assertEquals("Workflow draft created via AI Coding", draftStep.message());
        assertEquals("wf-123", draftStep.evidence().get("workflowId"));
        assertEquals("demo-page-assistant", draftStep.evidence().get("keySlug"));
        assertEquals("/workflows/wf-123/studio", draftStep.evidence().get("studioUrl"));
        assertEquals(runtimeVerification, draftStep.evidence().get("runtimeVerification"));
        verify(stepMapper).insert(any(AiAccessStepEntity.class));
        verify(stepMapper).updateById(any(AiAccessStepEntity.class));
        verify(sessionMapper).updateById(any(AiAccessSessionEntity.class));
    }

    @Test
    void reportWorkflowAiCodingResultRejectsMissingSession() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.reportWorkflowAiCodingResult(
                        1L,
                        "missing-session",
                        new AiAccessSessionService.WorkflowAiCodingResultRequest(
                                "wf-123",
                                "demo-page-assistant",
                                "Demo Page Assistant",
                                "PASS",
                                "done",
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                null)));

        assertTrue(error.getMessage().contains("access session not found"));
    }

    @Test
    void resetWorkflowAiCodingResultDeletesDraftWorkflowAndStep() {
        AiAccessSessionEntity session = session("session-page");
        session.setScenario("PAGE_ASSISTANT");
        AiAccessStepEntity draftStep = step(
                "session-page",
                AiAccessSessionService.WORKFLOW_AI_CODING_DRAFT_STEP_KEY,
                "PASS");
        draftStep.setEvidenceJson("{\"workflowId\":\"wf-123\"}");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step("session-page", "page-manifest", "PASS"),
                draftStep
        ));

        AiAccessSessionService.AccessSessionView view = service.resetWorkflowAiCodingResult(
                1L,
                "session-page",
                true);

        assertTrue(view.steps().stream()
                .noneMatch(step -> AiAccessSessionService.WORKFLOW_AI_CODING_DRAFT_STEP_KEY.equals(step.stepKey())));
        verify(workflowDefinitionService).delete("wf-123");
        verify(stepMapper).deleteById(draftStep.getId());
        verify(sessionMapper).updateById(any(AiAccessSessionEntity.class));
    }

    @Test
    void resetWorkflowAiCodingResultIgnoresAlreadyDeletedWorkflow() {
        AiAccessSessionEntity session = session("session-page");
        session.setScenario("PAGE_ASSISTANT");
        AiAccessStepEntity draftStep = step(
                "session-page",
                AiAccessSessionService.WORKFLOW_AI_CODING_DRAFT_STEP_KEY,
                "PASS");
        draftStep.setEvidenceJson("{\"workflowId\":\"wf-missing\"}");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(stepMapper.selectList(any())).thenReturn(List.of(draftStep));
        doThrow(new IllegalArgumentException("workflow not found: wf-missing"))
                .when(workflowDefinitionService)
                .delete("wf-missing");

        AiAccessSessionService.AccessSessionView view = service.resetWorkflowAiCodingResult(
                1L,
                "session-page",
                true);

        assertTrue(view.steps().stream()
                .noneMatch(step -> AiAccessSessionService.WORKFLOW_AI_CODING_DRAFT_STEP_KEY.equals(step.stepKey())));
        verify(stepMapper).deleteById(draftStep.getId());
        verify(sessionMapper).updateById(any(AiAccessSessionEntity.class));
    }

    @Test
    void bindPageAssistantTargetUpdatesSessionTargetAndMetadata() {
        AiAccessSessionEntity session = session("session-page");
        session.setScenario("PAGE_ASSISTANT");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step("session-page", "page-manifest", "TODO"),
                step("session-page", "route-detection", "TODO")
        ));

        AiAccessSessionService.AccessSessionView view = service.bindPageAssistantTarget(
                1L,
                "session-page",
                new AiAccessSessionService.PageAssistantTargetRequest(
                        "teamArchive.list",
                        "/team-build/depart-management",
                        List.of("getPageState", "search", "readTable")));

        assertEquals("teamArchive.list", view.targetPageKey());
        assertEquals("/team-build/depart-management", view.targetRoute());
        ArgumentCaptor<AiAccessSessionEntity> sessionCaptor = ArgumentCaptor.forClass(AiAccessSessionEntity.class);
        verify(sessionMapper).updateById(sessionCaptor.capture());
        assertEquals("teamArchive.list", sessionCaptor.getValue().getTargetPageKey());
        assertTrue(sessionCaptor.getValue().getMetadataJson().contains("readTable"));
    }

    @Test
    void applyPageAssistantPageRegistrationStoresEvidenceAndKeepsBrowserWarnNonBlocking() {
        AiAccessSessionEntity session = session("session-page");
        session.setScenario("PAGE_ASSISTANT");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step("session-page", "page-manifest", "TODO"),
                step("session-page", "route-detection", "TODO"),
                step("session-page", "frontend-handler", "TODO"),
                step("session-page", "page-registry", "TODO"),
                step("session-page", "browser-verify", "TODO"),
                step("session-page", "handoff-summary", "TODO")
        ));

        AiAccessSessionService.PageAssistantPageRegisterSessionResult result =
                service.applyPageAssistantPageRegistration(
                        1L,
                        new AiAccessSessionService.PageAssistantPageRegisterRequest(
                                "session-page",
                                "Cursor",
                                "teamArchive.list",
                                "班组档案",
                                "/team-build/depart-management",
                                "angular",
                                "12",
                                "__REACHAI_PAGE_BRIDGE__",
                                true,
                                List.of(new AiAccessSessionService.PageAssistantFileEvidence(
                                        "src/app/list.component.ts",
                                        "page-component",
                                        true,
                                        null)),
                                List.of(new PageActionCatalogContracts.PageActionDefinitionRequest(
                                        "getPageState",
                                        "读取页面状态",
                                        "读取筛选、分页和表格",
                                        false,
                                        Map.of(),
                                        Map.of(),
                                        Map.of(),
                                        List.of(),
                                        Map.of("riskLevel", "LOW"))),
                                Map.of(
                                        "build", Map.of("status", "PASS", "command", "npm run build"),
                                        "staticHandler", Map.of("status", "PASS", "handlerCount", 5),
                                        "browser", Map.of("status", "WARN", "message", "缺少业务登录态")),
                                "已完成查询类 Page Action MVP"),
                        new PageCatalogRegisterResult("demo-service", "bzjs3", "teamArchive.list", 1));

        assertEquals("teamArchive.list", result.session().targetPageKey());
        assertEquals("/team-build/depart-management", result.session().targetRoute());
        assertEquals(1, result.fileEvidence().size());
        assertEquals("HASH_MISSING", result.fileEvidence().get(0).validationStatus());
        assertTrue(result.session().steps().stream()
                .anyMatch(step -> "page-manifest".equals(step.stepKey())
                        && step.message() != null
                        && step.message().contains("hash missing")));
        verify(stepMapper, org.mockito.Mockito.atLeast(5)).updateById(any(AiAccessStepEntity.class));
        verify(sessionMapper).updateById(any(AiAccessSessionEntity.class));
    }

    @Test
    void runChecksPersistsCheckResultsIntoMappedSteps() {
        AiAccessSessionEntity session = session("session-1");
        AiAccessStepEntity projectKind = step("session-1", "project-manifest", "TODO");
        AiAccessStepEntity gateway = step("session-1", "gateway-route", "TODO");
        AiAccessStepEntity connectivity = step("session-1", "connectivity-check", "TODO");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(stepMapper.selectList(any())).thenReturn(List.of(projectKind, gateway, connectivity));
        when(sdkAccessCheckService.check(1L, new SdkAccessCheckService.SdkAccessCheckRequest(
                10L,
                Map.of("teamName", "A"),
                "http://localhost:8080",
                "/api/reachai/embed-token")))
                .thenReturn(new SdkAccessCheckService.SdkAccessCheckResponse(
                        1L,
                        "demo-service",
                        SdkAccessCheckService.CheckStatus.WARN,
                        List.of(),
                        List.of(
                                new SdkAccessCheckService.SdkAccessCheckItem(
                                        "project-kind", "Project", SdkAccessCheckService.CheckStatus.PASS, "ok", "REGISTERED"),
                                new SdkAccessCheckService.SdkAccessCheckItem(
                                        "gateway-route", "Gateway", SdkAccessCheckService.CheckStatus.PASS, "ok", "http://localhost:8080"),
                                new SdkAccessCheckService.SdkAccessCheckItem(
                                        "api-invocation", "Invocation", SdkAccessCheckService.CheckStatus.WARN, "missing args", "apiAssetId=missing")
                        )));

        AiAccessSessionService.AccessCheckRunResponse response = service.runChecks(
                1L,
                "session-1",
                new SdkAccessCheckService.SdkAccessCheckRequest(
                        10L,
                        Map.of("teamName", "A"),
                        "http://localhost:8080",
                        "/api/reachai/embed-token"));

        assertEquals(SdkAccessCheckService.CheckStatus.WARN, response.checkResult().overallStatus());
        assertEquals("WARN", response.session().status());
        verify(stepMapper, org.mockito.Mockito.atLeast(3)).updateById(any(AiAccessStepEntity.class));
        verify(sessionMapper).updateById(any(AiAccessSessionEntity.class));
    }

    @Test
    void runChecksPreservesExternalStepReportAndAppendsPlatformEvidence() {
        AiAccessSessionEntity session = session("session-1");
        AiAccessStepEntity gateway = step("session-1", "gateway-route", "PASS");
        gateway.setReportedBy("Cursor");
        gateway.setMessage("Gateway security chain compiled.");
        gateway.setEvidenceJson("{\"command\":\"mvn -pl qmssmp-gateway test\",\"exitCode\":0}");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(stepMapper.selectList(any())).thenReturn(List.of(gateway));
        when(sdkAccessCheckService.check(1L, new SdkAccessCheckService.SdkAccessCheckRequest(
                null,
                Map.of(),
                null,
                null)))
                .thenReturn(new SdkAccessCheckService.SdkAccessCheckResponse(
                        1L,
                        "demo-service",
                        SdkAccessCheckService.CheckStatus.WARN,
                        List.of(),
                        List.of(new SdkAccessCheckService.SdkAccessCheckItem(
                                "gateway-route",
                                "Gateway",
                                SdkAccessCheckService.CheckStatus.WARN,
                                "gatewayBaseUrl missing",
                                "manual-confirm-required"))));

        AiAccessSessionService.AccessCheckRunResponse response = service.runChecks(
                1L,
                "session-1",
                new SdkAccessCheckService.SdkAccessCheckRequest(null, Map.of(), null, null));

        AiAccessSessionService.AccessStepView gatewayStep = response.session().steps().stream()
                .filter(step -> "gateway-route".equals(step.stepKey()))
                .findFirst()
                .orElseThrow();
        assertEquals("PASS", gatewayStep.status());
        assertEquals("Cursor", gatewayStep.reportedBy());
        assertEquals("Gateway security chain compiled.", gatewayStep.message());
        assertEquals("mvn -pl qmssmp-gateway test", gatewayStep.evidence().get("command"));
        assertTrue(gatewayStep.evidence().containsKey("platformCheck"));
    }

    @Test
    void runPageAssistantChecksPersistsCatalogResultsIntoPageAssistantSteps() {
        AiAccessSessionEntity session = session("session-page");
        session.setScenario("PAGE_ASSISTANT");
        session.setTargetPageKey("teamArchive.list");
        session.setTargetRoute("/teams/archive");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(scanProjectService.getById(1L)).thenReturn(project());
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step("session-page", "page-manifest", "TODO"),
                step("session-page", "route-detection", "TODO"),
                step("session-page", "page-registry", "TODO"),
                step("session-page", "frontend-handler", "TODO"),
                step("session-page", "browser-verify", "TODO")
        ));
        when(pageRegistryMapper.selectList(any())).thenReturn(List.of(page("teamArchive.list", "/teams/archive")));
        when(pageActionRegistryMapper.selectList(any())).thenReturn(List.of(
                action("teamArchive.list", "getPageState", false),
                action("teamArchive.list", "search", false),
                action("teamArchive.list", "readTable", false)
        ));

        AiAccessSessionService.PageAssistantCheckRunResponse response = service.runPageAssistantChecks(
                1L,
                "session-page",
                new AiAccessSessionService.PageAssistantCheckRequest(
                        "teamArchive.list",
                        "/teams/archive",
                        List.of("getPageState", "search", "readTable"),
                        null,
                        null));

        assertEquals("teamArchive.list", response.checkResult().pageKey());
        assertEquals("WARN", response.checkResult().overallStatus());
        assertTrue(response.checkResult().checks().stream()
                .anyMatch(check -> "page-registry".equals(check.key()) && "PASS".equals(check.status())));
        assertTrue(response.checkResult().checks().stream()
                .anyMatch(check -> "browser-verify-static".equals(check.key()) && "PASS".equals(check.status())));
        assertTrue(response.checkResult().checks().stream()
                .anyMatch(check -> "browser-verify-runtime".equals(check.key()) && "SKIPPED".equals(check.status())));
        assertEquals("WARN", response.session().status());
        assertTrue(response.session().steps().stream()
                .anyMatch(step -> "browser-verify".equals(step.stepKey()) && "WARN".equals(step.status())));
        verify(stepMapper, org.mockito.Mockito.atLeast(4)).updateById(any(AiAccessStepEntity.class));
        verify(sessionMapper).updateById(any(AiAccessSessionEntity.class));
    }

    @Test
    void runPageAssistantChecksNeverReportsRuntimePassWithoutProbe() {
        AiAccessSessionEntity session = session("session-page");
        session.setScenario("PAGE_ASSISTANT");
        session.setTargetPageKey("teamArchive.list");
        session.setTargetRoute("/teams/archive");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(scanProjectService.getById(1L)).thenReturn(project());
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step("session-page", "browser-verify", "TODO")
        ));
        when(pageRegistryMapper.selectList(any())).thenReturn(List.of(page("teamArchive.list", "/teams/archive")));
        when(pageActionRegistryMapper.selectList(any())).thenReturn(List.of(
                action("teamArchive.list", "getPageState", false),
                action("teamArchive.list", "search", false)
        ));

        AiAccessSessionService.PageAssistantCheckRunResponse response = service.runPageAssistantChecks(
                1L,
                "session-page",
                new AiAccessSessionService.PageAssistantCheckRequest(
                        "teamArchive.list",
                        "/teams/archive",
                        List.of("getPageState", "search"),
                        null,
                        null));

        assertTrue(response.checkResult().checks().stream()
                .noneMatch(check -> "browser-verify-runtime".equals(check.key()) && "PASS".equals(check.status())));
        assertTrue(response.checkResult().checks().stream()
                .anyMatch(check -> "browser-verify-static".equals(check.key()) && "PASS".equals(check.status())));
        assertTrue(response.checkResult().checks().stream()
                .anyMatch(check -> "browser-verify-runtime".equals(check.key()) && "SKIPPED".equals(check.status())));
    }

    @Test
    void normalizeFileEvidenceAcceptsStringShorthandShape() {
        List<AiAccessSessionService.PageAssistantFileEvidence> normalized =
                AiAccessSessionService.normalizeFileEvidence(List.of(
                        new AiAccessSessionService.PageAssistantFileEvidence("src/app/list.component.ts", "unknown", null, null)));

        assertEquals(1, normalized.size());
        assertEquals("src/app/list.component.ts", normalized.get(0).path());
        assertEquals("unknown", normalized.get(0).role());
    }

    @Test
    void runPageAssistantChecksDoesNotDowngradeAiReportedPassToWarn() {
        AiAccessSessionEntity session = session("session-page");
        session.setScenario("PAGE_ASSISTANT");
        session.setTargetPageKey("teamArchive.list");
        session.setTargetRoute("/team-build/depart-management");
        AiAccessStepEntity manifest = step("session-page", "page-manifest", "PASS");
        manifest.setReportedBy("cursor");
        AiAccessStepEntity registry = step("session-page", "page-registry", "PASS");
        registry.setReportedBy("cursor");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(scanProjectService.getById(1L)).thenReturn(project());
        when(stepMapper.selectList(any())).thenReturn(List.of(
                manifest,
                step("session-page", "route-detection", "PASS"),
                registry,
                step("session-page", "frontend-handler", "PASS"),
                step("session-page", "browser-verify", "WARN")
        ));
        when(pageRegistryMapper.selectList(any())).thenReturn(List.of());
        when(pageActionRegistryMapper.selectList(any())).thenReturn(List.of());

        AiAccessSessionService.PageAssistantCheckRunResponse response = service.runPageAssistantChecks(
                1L,
                "session-page",
                new AiAccessSessionService.PageAssistantCheckRequest(
                        null,
                        null,
                        List.of("getPageState", "search"),
                        null,
                        null));

        assertEquals("WARN", response.checkResult().overallStatus());
        assertTrue(response.session().steps().stream()
                .anyMatch(step -> "page-registry".equals(step.stepKey()) && "PASS".equals(step.status())));
    }

    @Test
    void runPageAssistantChecksRuntimePassRequiresInvokeEvidence() {
        AiAccessSessionEntity session = session("session-page");
        session.setScenario("PAGE_ASSISTANT");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(scanProjectService.getById(1L)).thenReturn(project());
        when(stepMapper.selectList(any())).thenReturn(List.of(step("session-page", "browser-verify", "TODO")));
        when(pageRegistryMapper.selectList(any())).thenReturn(List.of());
        when(pageActionRegistryMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> fakePassWithoutInvoke = Map.of("status", "PASS", "message", "claimed pass");
        AiAccessSessionService.PageAssistantCheckRunResponse response = service.runPageAssistantChecks(
                1L,
                "session-page",
                new AiAccessSessionService.PageAssistantCheckRequest(
                        "teamArchive.list",
                        "/teams/archive",
                        List.of("getPageState"),
                        "http://localhost:9200",
                        fakePassWithoutInvoke));

        assertTrue(response.checkResult().checks().stream()
                .anyMatch(check -> "browser-verify-runtime".equals(check.key()) && "WARN".equals(check.status())));
    }

    @Test
    void runPageAssistantChecksAcceptsRuntimePassWhenInvokeEvidencePresent() {
        AiAccessSessionEntity session = session("session-page");
        session.setScenario("PAGE_ASSISTANT");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(scanProjectService.getById(1L)).thenReturn(project());
        when(stepMapper.selectList(any())).thenReturn(List.of(step("session-page", "browser-verify", "TODO")));
        when(pageRegistryMapper.selectList(any())).thenReturn(List.of(page("teamArchive.list", "/teams/archive")));
        when(pageActionRegistryMapper.selectList(any())).thenReturn(List.of(action("teamArchive.list", "getPageState", false)));

        Map<String, Object> runtime = Map.of(
                "status", "PASS",
                "message", "readonly invoke ok",
                "frontendUrl", "http://localhost:9200",
                "bridgeExists", true,
                "invokedActions", List.of("getPageState"),
                "redactedResults", List.of(Map.of("actionKey", "getPageState", "status", "SUCCESS")));
        AiAccessSessionService.PageAssistantCheckRunResponse response = service.runPageAssistantChecks(
                1L,
                "session-page",
                new AiAccessSessionService.PageAssistantCheckRequest(
                        "teamArchive.list",
                        "/teams/archive",
                        List.of("getPageState"),
                        "http://localhost:9200",
                        runtime));

        assertTrue(response.checkResult().checks().stream()
                .anyMatch(check -> "browser-verify-runtime".equals(check.key()) && "PASS".equals(check.status())));
    }

    @Test
    void runPageAssistantChecksFailsWhenExpectedActionIsNotRegisteredInRuntime() {
        AiAccessSessionEntity session = session("session-page");
        session.setScenario("PAGE_ASSISTANT");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(scanProjectService.getById(1L)).thenReturn(project());
        when(stepMapper.selectList(any())).thenReturn(List.of(step("session-page", "browser-verify", "TODO")));
        when(pageRegistryMapper.selectList(any())).thenReturn(List.of(page("teamArchive.list", "/teams/archive")));
        when(pageActionRegistryMapper.selectList(any())).thenReturn(List.of(
                action("teamArchive.list", "getPageState", false),
                action("teamArchive.list", "setFilters", false)));

        Map<String, Object> runtime = Map.of(
                "status", "PASS",
                "message", "readonly invoke ok but setFilters is missing",
                "frontendUrl", "http://localhost:9200",
                "bridgeExists", true,
                "listedActions", List.of("getPageState"),
                "invokedActions", List.of("getPageState"),
                "redactedResults", List.of(Map.of("actionKey", "getPageState", "status", "SUCCESS")));
        AiAccessSessionService.PageAssistantCheckRunResponse response = service.runPageAssistantChecks(
                1L,
                "session-page",
                new AiAccessSessionService.PageAssistantCheckRequest(
                        "teamArchive.list",
                        "/teams/archive",
                        List.of("getPageState", "setFilters"),
                        "http://localhost:9200",
                        runtime));

        assertTrue(response.checkResult().checks().stream()
                .anyMatch(check -> "browser-verify-runtime".equals(check.key()) && "FAIL".equals(check.status())));
    }

    @Test
    void runPageAssistantChecksPreservesAiEvidenceAfterPlatformCheck() {
        AiAccessSessionEntity session = session("session-page");
        session.setScenario("PAGE_ASSISTANT");
        session.setTargetPageKey("teamArchive.list");
        AiAccessStepEntity browser = step("session-page", "browser-verify", "PASS");
        browser.setReportedBy("cursor");
        browser.setMessage("AI reported runtime evidence");
        browser.setEvidenceJson("{\"invokedActions\":[\"getPageState\"],\"note\":\"from-ai\"}");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(scanProjectService.getById(1L)).thenReturn(project());
        when(stepMapper.selectList(any())).thenReturn(List.of(browser));
        when(pageRegistryMapper.selectList(any())).thenReturn(List.of());
        when(pageActionRegistryMapper.selectList(any())).thenReturn(List.of());

        AiAccessSessionService.PageAssistantCheckRunResponse response = service.runPageAssistantChecks(
                1L,
                "session-page",
                new AiAccessSessionService.PageAssistantCheckRequest(
                        null, null, List.of("getPageState"), null, null));

        AiAccessSessionService.AccessStepView browserStep = response.session().steps().stream()
                .filter(step -> "browser-verify".equals(step.stepKey()))
                .findFirst()
                .orElseThrow();
        assertEquals("PASS", browserStep.status());
        assertEquals("cursor", browserStep.reportedBy());
        assertEquals("AI reported runtime evidence", browserStep.message());
        assertTrue(browserStep.evidence().containsKey("platformCheck"));
        assertEquals("from-ai", String.valueOf(browserStep.evidence().get("note")));
    }

    @Test
    void enrichFileEvidenceMarksHashMissingForStringShorthand() {
        List<AiAccessSessionService.PageAssistantFileEvidenceView> views = AiAccessSessionService.enrichFileEvidence(
                List.of(new AiAccessSessionService.PageAssistantFileEvidence("src/app/list.component.ts", "unknown", null, null)));
        assertEquals("HASH_MISSING", views.get(0).validationStatus());
        assertTrue(views.get(0).validationMessage().contains("hash missing"));
    }

    @Test
    void reportStepAcceptsBrowserVerifyStaticAliasButKeepsParentWarn() {
        AiAccessSessionEntity session = session("session-page");
        session.setScenario("PAGE_ASSISTANT");
        AiAccessStepEntity browser = step("session-page", "browser-verify", "TODO");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(stepMapper.selectList(any())).thenReturn(List.of(browser));

        AiAccessSessionService.AccessSessionView view = service.reportStep(
                1L,
                "session-page",
                "browser-verify-static",
                new AiAccessSessionService.StepReportRequest(
                        "PASS",
                        "static only",
                        List.of("src/app/shared/reachai/page-actions.ts"),
                        Map.of("registeredActions", List.of("getPageState")),
                        "cursor"));

        AiAccessSessionService.AccessStepView browserStep = view.steps().stream()
                .filter(step -> "browser-verify".equals(step.stepKey()))
                .findFirst()
                .orElseThrow();
        assertEquals("WARN", browserStep.status());
        assertTrue(browserStep.message().contains("parent step remains WARN"));
        assertTrue(browserStep.evidence().containsKey("browserStatic"));
        verify(stepMapper).updateById(any(AiAccessStepEntity.class));
        verify(sessionMapper).updateById(any(AiAccessSessionEntity.class));
    }

    private static ScanProjectEntity project() {
        ScanProjectEntity entity = new ScanProjectEntity();
        entity.setId(1L);
        entity.setName("Demo Service");
        entity.setProjectCode("demo-service");
        entity.setProjectKind("REGISTERED");
        return entity;
    }

    private static AiAccessSessionEntity session(String sessionId) {
        AiAccessSessionEntity entity = new AiAccessSessionEntity();
        entity.setId(100L);
        entity.setSessionId(sessionId);
        entity.setProjectId(1L);
        entity.setProjectCode("demo-service");
        entity.setScenario("SDK_ACCESS");
        entity.setStatus("OPEN");
        return entity;
    }

    private static AiAccessStepEntity step(String sessionId, String stepKey, String status) {
        AiAccessStepEntity entity = new AiAccessStepEntity();
        entity.setId((long) Math.abs(stepKey.hashCode()));
        entity.setSessionId(sessionId);
        entity.setProjectId(1L);
        entity.setStepKey(stepKey);
        entity.setTitle(stepKey);
        entity.setStatus(status);
        return entity;
    }

    private static PageRegistryEntity page(String pageKey, String routePattern) {
        PageRegistryEntity entity = new PageRegistryEntity();
        entity.setProjectCode("demo-service");
        entity.setPageKey(pageKey);
        entity.setName("Team Archive");
        entity.setRoutePattern(routePattern);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private static PageActionRegistryEntity action(String pageKey, String actionKey, boolean confirmRequired) {
        PageActionRegistryEntity entity = new PageActionRegistryEntity();
        entity.setProjectCode("demo-service");
        entity.setPageKey(pageKey);
        entity.setActionKey(actionKey);
        entity.setTitle(actionKey);
        entity.setConfirmRequired(confirmRequired);
        entity.setStatus("ACTIVE");
        return entity;
    }
}
