package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactContractRef;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ArtifactNextAction;
import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowDraftValidationView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowDraftView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowEngineeringDraftView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchWorkflowTraceReadinessApplicationService.WorkflowAcceptanceTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageWorkbenchTaskProviderTest {

    private ObjectMapper objectMapper;
    private AiCodingContractResourceLoader resources;
    private PageCatalogApplicationService pageCatalog;
    private PageAnalysisApplicationService pageAnalysis;
    private CapabilityProjectOnboardingClient capabilityClient;
    private RuntimeProxyClient runtimeClient;
    private PageWorkbenchBrowserReadinessApplicationService browserReadiness;
    private PageWorkbenchWorkflowTraceReadinessApplicationService
            workflowTraceReadiness;
    private PageWorkbenchReleaseReadinessApplicationService releaseReadiness;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        resources = new AiCodingContractResourceLoader(objectMapper);
        pageCatalog = mock(PageCatalogApplicationService.class);
        pageAnalysis = mock(PageAnalysisApplicationService.class);
        capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        runtimeClient = mock(RuntimeProxyClient.class);
        browserReadiness = mock(
                PageWorkbenchBrowserReadinessApplicationService.class);
        workflowTraceReadiness = mock(
                PageWorkbenchWorkflowTraceReadinessApplicationService.class);
        releaseReadiness = mock(
                PageWorkbenchReleaseReadinessApplicationService.class);
        when(releaseReadiness.waitingForArtifact()).thenReturn(
                readiness("PENDING"));
        when(pageCatalog.findPageByKey("orders", "orders.detail"))
                .thenReturn(Optional.of(page()));
        when(capabilityClient.getOnboardingProjectById(7L))
                .thenReturn(Map.of(
                        "id", 7L,
                        "projectCode", "orders",
                        "name", "Orders"));
        when(capabilityClient.listProjectTools(7L)).thenReturn(List.of());
    }

    @Test
    void workflowEngineeringCreatesDraftButStillRequiresReachAiAcceptance() {
        PageWorkbenchTaskProvider provider = provider(
                PageWorkbenchTaskProvider.WORKFLOW_ENGINEERING,
                "workflow-engineering-report-v1");
        when(pageCatalog.findAction(
                "orders",
                "orders.detail",
                "getPageState")).thenReturn(Optional.of(activeAction()));
        WorkflowEngineeringDraftView draft =
                new WorkflowEngineeringDraftView(
                        "reachai.page-workbench.workflow-engineering-draft.v1",
                        "ait_page_test",
                        "orders.detail",
                        "只读订单详情",
                        List.of("getPageState"),
                        List.of("src/views/orders/Detail.vue"),
                        List.of("只读取当前页面"),
                        List.of(),
                        new WorkflowDraftView(
                                "wf-orders-detail",
                                "orders-detail-page-assistant",
                                "订单详情页面助手",
                                "DRAFT",
                                LocalDateTime.of(2026, 7, 26, 10, 1)),
                        new WorkflowDraftValidationView(
                                true,
                                List.of(),
                                List.of()));
        when(runtimeClient.createPageWorkbenchWorkflowDraft(
                org.mockito.ArgumentMatchers.eq("orders"),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(ResponseEntity.ok(draft));

        ArtifactApplyResult result = provider.applyArtifact(
                task(PageWorkbenchTaskProvider.WORKFLOW_ENGINEERING),
                artifact(
                        "reachai.workflow-engineering-report",
                        example("workflow-engineering-report-v1")));

        assertTrue(result.applied());
        assertEquals(
                ArtifactNextAction.ACCEPTANCE_REQUIRED,
                result.nextAction());
        assertEquals(
                "wf-orders-detail",
                result.domainResult()
                        .path("workflowEngineering")
                        .path("workflow")
                        .path("id")
                        .asText());
        verify(runtimeClient).createPageWorkbenchWorkflowDraft(
                org.mockito.ArgumentMatchers.eq("orders"),
                org.mockito.ArgumentMatchers.argThat(request ->
                        "ait_page_test".equals(request.get("taskId"))
                                && "orders.detail".equals(
                                request.get("pageKey"))));
    }

    @Test
    void implementationRequiresPassingTestsAndBrowserEvidence() {
        PageWorkbenchTaskProvider provider = provider(
                PageWorkbenchTaskProvider.CODE_IMPLEMENTATION,
                "code-implementation-report-v1");
        ObjectNode passing = example("code-implementation-report-v1");

        ArtifactApplyResult accepted = provider.applyArtifact(
                task(PageWorkbenchTaskProvider.CODE_IMPLEMENTATION),
                artifact("reachai.code-implementation-report", passing));
        assertTrue(accepted.applied());
        assertEquals(
                ArtifactNextAction.ACCEPTANCE_REQUIRED,
                accepted.nextAction());

        ObjectNode failedBrowser = passing.deepCopy();
        ((ObjectNode) failedBrowser.path("browserVerification"))
                .put("passed", false);
        ArtifactApplyResult rejected = provider.applyArtifact(
                task(PageWorkbenchTaskProvider.CODE_IMPLEMENTATION),
                artifact(
                        "reachai.code-implementation-report",
                        failedBrowser));
        assertFalse(rejected.applied());
        assertEquals(ArtifactNextAction.STAY_APPLIED, rejected.nextAction());
        assertNotNull(rejected.domainResult());
    }

    @Test
    void implementationRejectsReportedTestFailure() {
        PageWorkbenchTaskProvider provider = provider(
                PageWorkbenchTaskProvider.CODE_IMPLEMENTATION,
                "code-implementation-report-v1");
        ObjectNode content = example("code-implementation-report-v1");
        ((ObjectNode) content.path("tests").path(0)).put("status", "FAIL");

        ArtifactApplyResult result = provider.applyArtifact(
                task(PageWorkbenchTaskProvider.CODE_IMPLEMENTATION),
                artifact("reachai.code-implementation-report", content));

        assertFalse(result.applied());
        assertEquals(ArtifactNextAction.STAY_APPLIED, result.nextAction());
    }

    @Test
    void browserAcceptanceFailureIsAppliedAsTerminalEvidence() {
        PageWorkbenchTaskProvider provider = provider(
                PageWorkbenchTaskProvider.BROWSER_ACCEPTANCE,
                "browser-acceptance-report-v1");
        ObjectNode content = example("browser-acceptance-report-v1");
        content.put("passed", false);

        ArtifactApplyResult result = provider.applyArtifact(
                task(PageWorkbenchTaskProvider.BROWSER_ACCEPTANCE),
                artifact("reachai.browser-acceptance-report", content));

        assertTrue(result.applied());
        assertEquals(ArtifactNextAction.FAIL, result.nextAction());
        assertFalse(result.domainResult().path("reportedPassed").asBoolean());
    }

    @Test
    void passingBrowserAcceptanceStillRequiresUserConfirmation() {
        PageWorkbenchTaskProvider provider = provider(
                PageWorkbenchTaskProvider.BROWSER_ACCEPTANCE,
                "browser-acceptance-report-v1");

        ArtifactApplyResult result = provider.applyArtifact(
                task(PageWorkbenchTaskProvider.BROWSER_ACCEPTANCE),
                artifact(
                        "reachai.browser-acceptance-report",
                        example("browser-acceptance-report-v1")));

        assertTrue(result.applied());
        assertEquals(
                ArtifactNextAction.ACCEPTANCE_REQUIRED,
                result.nextAction());
    }

    @Test
    void browserAcceptanceRequiresServerObservedCurrentPageConversation() {
        PageWorkbenchTaskProvider provider = provider(
                PageWorkbenchTaskProvider.BROWSER_ACCEPTANCE,
                "browser-acceptance-report-v1");
        TaskDescriptor task = task(
                PageWorkbenchTaskProvider.BROWSER_ACCEPTANCE);
        when(browserReadiness.evaluate(
                "orders",
                "orders.detail",
                task.startedAt())).thenReturn(browserReadiness("PASS"));
        when(workflowTraceReadiness.evaluate(
                "orders",
                "orders.detail",
                task.startedAt(),
                null,
                null)).thenReturn(workflowTraceReadiness("PASS"));

        List<ReadinessItem> readiness = provider.readiness(task);

        assertEquals("PASS", readiness.get(0).status());
        assertEquals("PASS", readiness.get(1).status());
        assertEquals(
                List.of(
                        PageWorkbenchBrowserReadinessApplicationService.KEY,
                        PageWorkbenchWorkflowTraceReadinessApplicationService.KEY),
                provider.acceptanceReadinessKeys());
        verify(browserReadiness).evaluate(
                "orders",
                "orders.detail",
                task.startedAt());
        verify(workflowTraceReadiness).evaluate(
                "orders",
                "orders.detail",
                task.startedAt(),
                null,
                null);
    }

    @Test
    void browserArtifactTraceIsCheckedAgainstTheBoundWorkflowTarget() {
        PageWorkbenchTaskProvider provider = provider(
                PageWorkbenchTaskProvider.BROWSER_ACCEPTANCE,
                "browser-acceptance-report-v1");
        TaskDescriptor task = taskWithWorkflow();
        ObjectNode content = example("browser-acceptance-report-v1");
        content.put("traceId", "trace-orders");
        ArtifactApplyResult applied = provider.applyArtifact(
                task,
                artifact("reachai.browser-acceptance-report", content));
        WorkflowAcceptanceTarget target = new WorkflowAcceptanceTarget(
                "wf-orders",
                21L,
                "v1.0.0",
                "订单页面助手");
        when(browserReadiness.evaluate(
                "orders",
                "orders.detail",
                task.startedAt())).thenReturn(browserReadiness("PASS"));
        when(workflowTraceReadiness.evaluate(
                "orders",
                "orders.detail",
                task.startedAt(),
                target,
                "trace-orders")).thenReturn(workflowTraceReadiness("PASS"));

        List<ReadinessItem> readiness = provider.readiness(
                task,
                applied.domainResult());

        assertEquals(2, readiness.size());
        verify(workflowTraceReadiness).evaluate(
                "orders",
                "orders.detail",
                task.startedAt(),
                target,
                "trace-orders");
    }

    @Test
    void browserContextUsesAServerValidatedPublishedWorkflowTarget() {
        PageWorkbenchTaskProvider provider = provider(
                PageWorkbenchTaskProvider.BROWSER_ACCEPTANCE,
                "browser-acceptance-report-v1");
        TaskDescriptor task = taskWithWorkflow();
        WorkflowAcceptanceTarget target = new WorkflowAcceptanceTarget(
                "wf-orders",
                21L,
                "v1.0.0",
                "订单页面助手");
        when(workflowTraceReadiness.requirePublishedTarget(
                "orders",
                "orders.detail",
                target)).thenReturn(target);

        JsonNode context = provider.buildContext(task);

        assertEquals("wf-orders",
                context.path("workflowAcceptanceTarget")
                        .path("workflowId")
                        .asText());
        assertEquals(
                "Human-readable output uses Simplified Chinese; technical identities remain unchanged.",
                context.path("instructions").path("humanLanguage").asText());
        assertTrue(context.path("scope").path("requiredChecks").toString()
                .contains("Simplified Chinese"));
        verify(workflowTraceReadiness).requirePublishedTarget(
                "orders",
                "orders.detail",
                target);
    }

    @Test
    void preReleaseRequiresPassingChecksAndConcreteWorkflowVersion() {
        PageWorkbenchTaskProvider provider = provider(
                PageWorkbenchTaskProvider.PRE_RELEASE_CHECK,
                "pre-release-report-v1");
        ObjectNode passing = example("pre-release-report-v1");

        ArtifactApplyResult accepted = provider.applyArtifact(
                task(PageWorkbenchTaskProvider.PRE_RELEASE_CHECK),
                artifact("reachai.pre-release-report", passing));
        assertEquals(
                ArtifactNextAction.ACCEPTANCE_REQUIRED,
                accepted.nextAction());
        when(releaseReadiness.evaluate(
                "orders",
                "orders.detail",
                "wf_demo",
                "1.0.0")).thenReturn(readiness("PASS"));
        List<ReadinessItem> readiness = provider.readiness(
                task(PageWorkbenchTaskProvider.PRE_RELEASE_CHECK),
                accepted.domainResult());
        assertEquals("PASS", readiness.get(0).status());
        assertEquals(
                List.of(PageWorkbenchReleaseReadinessApplicationService.KEY),
                provider.acceptanceReadinessKeys());
        verify(releaseReadiness).evaluate(
                "orders",
                "orders.detail",
                "wf_demo",
                "1.0.0");

        ObjectNode missingVersion = passing.deepCopy();
        missingVersion.putNull("workflowVersion");
        ArtifactApplyResult failed = provider.applyArtifact(
                task(PageWorkbenchTaskProvider.PRE_RELEASE_CHECK),
                artifact("reachai.pre-release-report", missingVersion));
        assertTrue(failed.applied());
        assertEquals(ArtifactNextAction.FAIL, failed.nextAction());
    }

    private PageWorkbenchTaskProvider provider(
            String taskKind,
            String resourcePrefix) {
        return new PageWorkbenchTaskProvider(
                taskKind,
                "READ_WRITE",
                "reachai." + resourcePrefix,
                resources.load(resourcePrefix + ".schema.json"),
                resources.load(resourcePrefix + ".example.json"),
                pageCatalog,
                pageAnalysis,
                capabilityClient,
                runtimeClient,
                browserReadiness,
                workflowTraceReadiness,
                releaseReadiness,
                objectMapper);
    }

    private TaskDescriptor task(String taskKind) {
        return new TaskDescriptor(
                "ait_page_test",
                7L,
                "orders",
                taskKind,
                taskKind,
                "v1",
                "TRAE",
                "页面任务",
                "完成当前页面目标",
                "READ_WRITE",
                "RUNNING",
                objectMapper.createObjectNode(),
                List.of(new TaskTargetView(
                        null,
                        "PAGE",
                        "orders.detail",
                        "PRIMARY",
                        "READ_ONLY",
                        objectMapper.createObjectNode())),
                LocalDateTime.of(2026, 7, 26, 10, 0),
                LocalDateTime.of(2026, 7, 26, 9, 55));
    }

    private TaskDescriptor taskWithWorkflow() {
        ObjectNode workflowSnapshot = objectMapper.createObjectNode();
        workflowSnapshot.put("workflowName", "订单页面助手");
        workflowSnapshot.put("workflowVersionId", 21L);
        workflowSnapshot.put("workflowVersion", "v1.0.0");
        return new TaskDescriptor(
                "ait_page_workflow_test",
                7L,
                "orders",
                PageWorkbenchTaskProvider.BROWSER_ACCEPTANCE,
                PageWorkbenchTaskProvider.BROWSER_ACCEPTANCE,
                "v1",
                "TRAE",
                "页面任务",
                "验收已发布页面助手",
                "READ_ONLY",
                "RUNNING",
                objectMapper.createObjectNode(),
                List.of(
                        new TaskTargetView(
                                null,
                                "PAGE",
                                "orders.detail",
                                "PRIMARY",
                                "READ_ONLY",
                                objectMapper.createObjectNode()),
                        new TaskTargetView(
                                null,
                                "WORKFLOW",
                                "wf-orders",
                                "RELATED",
                                "READ_ONLY",
                                workflowSnapshot)),
                LocalDateTime.of(2026, 7, 26, 10, 0),
                LocalDateTime.of(2026, 7, 26, 9, 55));
    }

    private PageView page() {
        return new PageView(
                1L,
                7L,
                "orders",
                "orders.detail",
                "orders",
                "订单",
                "订单详情",
                null,
                "/orders/:id",
                "src/views/orders/Detail.vue",
                "AI_CODING",
                "ACTIVE",
                null,
                null,
                List.of(),
                List.of());
    }

    private ActionView activeAction() {
        return new ActionView(
                11L,
                1L,
                7L,
                "orders",
                "orders.detail",
                "getPageState",
                "读取页面状态",
                "读取当前页面状态",
                "QUERY",
                "LOW",
                false,
                null,
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                List.of(),
                "src/views/orders/Detail.vue",
                "MANUAL",
                "ACTIVE",
                objectMapper.createObjectNode(),
                null);
    }

    private ArtifactEnvelope artifact(String contractKey, JsonNode content) {
        return new ArtifactEnvelope(
                "reachai.ai-coding.artifact.v1",
                "event-" + System.nanoTime(),
                "artifact-" + System.nanoTime(),
                new ArtifactContractRef(contractKey, "v1"),
                content,
                null);
    }

    private ObjectNode example(String resourcePrefix) {
        return (ObjectNode) resources.load(
                resourcePrefix + ".example.json").deepCopy();
    }

    private ReadinessItem readiness(String status) {
        return new ReadinessItem(
                PageWorkbenchReleaseReadinessApplicationService.KEY,
                "Workflow 发布前检查",
                status,
                "test",
                objectMapper.createObjectNode());
    }

    private ReadinessItem browserReadiness(String status) {
        return new ReadinessItem(
                PageWorkbenchBrowserReadinessApplicationService.KEY,
                "业务页面浏览器链路",
                status,
                "test",
                objectMapper.createObjectNode());
    }

    private ReadinessItem workflowTraceReadiness(String status) {
        return new ReadinessItem(
                PageWorkbenchWorkflowTraceReadinessApplicationService.KEY,
                "页面 Workflow 执行链路",
                status,
                "test",
                objectMapper.createObjectNode());
    }
}
