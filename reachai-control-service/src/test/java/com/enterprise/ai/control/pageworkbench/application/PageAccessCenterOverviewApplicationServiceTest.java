package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ConnectionView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageAccessCenterOverviewView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PublishedWorkflowView;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PageAccessCenterOverviewApplicationServiceTest {

    private final PageCatalogApplicationService pageCatalog =
            mock(PageCatalogApplicationService.class);
    private final PageAnalysisApplicationService pageAnalysis =
            mock(PageAnalysisApplicationService.class);
    private final AiCodingTaskApplicationService taskService =
            mock(AiCodingTaskApplicationService.class);
    private final PageWorkbenchPublishedApplicationService publishedService =
            mock(PageWorkbenchPublishedApplicationService.class);
    private final PageMapSummaryApplicationService pageMapSummaryService =
            mock(PageMapSummaryApplicationService.class);

    private PageAccessCenterOverviewApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PageAccessCenterOverviewApplicationService(
                pageCatalog,
                pageAnalysis,
                taskService,
                publishedService,
                pageMapSummaryService);
        when(pageCatalog.listPages(any(), anyBoolean()))
                .thenReturn(List.of(page()));
        when(pageAnalysis.list(any(), isNull(), isNull()))
                .thenReturn(List.of());
        when(taskService.list(isNull(), any(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of());
        when(publishedService.list(any(), isNull())).thenReturn(List.of());
        when(pageMapSummaryService.summary(any(), any()))
                .thenReturn(new PageWorkbenchContract.PageMapSummaryView(
                        false, null, null, null, null, null));
    }

    @Test
    void startsAtGoalSelectionWithoutInventingProgress() {
        PageAccessCenterOverviewView result = service.overview("orders");

        assertThat(result.schema())
                .isEqualTo(PageAccessCenterOverviewApplicationService.SCHEMA);
        assertThat(result.summary().discoveredCount()).isEqualTo(1);
        assertThat(result.catalogPages()).hasSize(1);
        assertThat(result.findings()).isEmpty();
        assertThat(result.tasks()).isEmpty();
        assertThat(result.pageMap().scanned()).isFalse();
        assertThat(result.pages()).singleElement().satisfies(journey -> {
            assertThat(journey.stage()).isEqualTo("GOAL_SELECTION");
            assertThat(journey.status()).isEqualTo("WAITING");
            assertThat(journey.currentStep()).isEqualTo(1);
            assertThat(journey.completedSteps()).isZero();
            assertThat(journey.nextAction().code()).isEqualTo("SELECT_GOAL");
        });
    }

    @Test
    void exposesWaitingUserAsPageActionRequired() {
        when(taskService.list(isNull(), any(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(task(
                        "implementation-1",
                        "CODE_IMPLEMENTATION",
                        "WAITING_USER",
                        List.of(pageTarget()))));

        PageAccessCenterOverviewView result = service.overview("orders");

        assertThat(result.pages()).singleElement().satisfies(journey -> {
            assertThat(journey.stage()).isEqualTo("AI_IMPLEMENTATION");
            assertThat(journey.status()).isEqualTo("ACTION_REQUIRED");
            assertThat(journey.currentStep()).isEqualTo(2);
            assertThat(journey.completedSteps()).isEqualTo(1);
            assertThat(journey.activeTaskId()).isEqualTo("implementation-1");
            assertThat(journey.nextAction().code()).isEqualTo("OPEN_TASK");
        });
        assertThat(result.summary().waitingCount()).isEqualTo(1);
        assertThat(result.summary().activeCount()).isZero();
    }

    @Test
    void requiresExplicitPublicationAfterAcceptedWorkflowTask() {
        when(taskService.list(isNull(), any(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(task(
                        "workflow-1",
                        "WORKFLOW_ENGINEERING",
                        "COMPLETED",
                        List.of(pageTarget()))));

        PageAccessCenterOverviewView result = service.overview("orders");

        assertThat(result.pages()).singleElement().satisfies(journey -> {
            assertThat(journey.stage()).isEqualTo("PUBLISH_CONFIRMATION");
            assertThat(journey.status()).isEqualTo("ACTION_REQUIRED");
            assertThat(journey.nextAction().code()).isEqualTo("PUBLISH_WORKFLOW");
            assertThat(journey.activeTaskId()).isEqualTo("workflow-1");
        });
    }

    @Test
    void completesOnlyForAcceptanceLockedToPublishedWorkflowVersion() {
        PublishedWorkflowView published = published();
        when(publishedService.list(any(), isNull()))
                .thenReturn(List.of(published));
        when(taskService.list(isNull(), any(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(task(
                        "acceptance-1",
                        "BROWSER_ACCEPTANCE",
                        "COMPLETED",
                        List.of(pageTarget(), workflowTarget(published)))));

        PageAccessCenterOverviewView result = service.overview("orders");

        assertThat(result.pages()).singleElement().satisfies(journey -> {
            assertThat(journey.stage()).isEqualTo("COMPLETE");
            assertThat(journey.status()).isEqualTo("COMPLETE");
            assertThat(journey.completedSteps()).isEqualTo(4);
            assertThat(journey.nextAction().code()).isEqualTo("VIEW_ONLINE");
        });
        assertThat(result.summary().completedCount()).isEqualTo(1);
    }

    @Test
    void doesNotTreatRuntimeFailureAsAnUnpublishedPage() {
        when(publishedService.list(any(), isNull()))
                .thenThrow(new IllegalStateException("runtime down"));

        PageAccessCenterOverviewView result = service.overview("orders");

        assertThat(result.runtimeAvailable()).isFalse();
        assertThat(result.pages()).singleElement().satisfies(journey -> {
            assertThat(journey.status()).isEqualTo("UNAVAILABLE");
            assertThat(journey.nextAction().code()).isEqualTo("REFRESH_STATUS");
            assertThat(journey.nextAction().enabled()).isFalse();
        });
        assertThat(result.summary().unavailableCount()).isEqualTo(1);
        assertThat(result.summary().waitingCount()).isZero();
    }

    @Test
    void keepsActiveAcceptanceInOneTopSummaryBucketWhenRuntimeIsUnavailable() {
        PublishedWorkflowView published = published();
        when(publishedService.list(any(), isNull()))
                .thenThrow(new IllegalStateException("runtime down"));
        when(taskService.list(isNull(), any(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(task(
                        "acceptance-running",
                        "BROWSER_ACCEPTANCE",
                        "RUNNING",
                        List.of(pageTarget(), workflowTarget(published)))));

        PageAccessCenterOverviewView result = service.overview("orders");

        assertThat(result.pages()).singleElement().satisfies(journey -> {
            assertThat(journey.stage()).isEqualTo("REAL_ACCEPTANCE");
            assertThat(journey.status()).isEqualTo("ACTIVE");
            assertThat(journey.currentStep()).isEqualTo(4);
            assertThat(journey.completedSteps()).isEqualTo(3);
        });
        assertThat(result.summary().awaitingAcceptanceCount()).isEqualTo(1);
        assertThat(result.summary().activeCount()).isZero();
        assertThat(result.summary().waitingCount()).isZero();
        assertThat(result.summary().unavailableCount()).isZero();
    }

    private PageView page() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 10, 0);
        return new PageView(
                10L,
                20L,
                "orders",
                "orders.list",
                "orders",
                "订单管理",
                "订单列表",
                "查看并维护订单",
                "/orders",
                "src/pages/orders.vue",
                "SDK",
                "ACTIVE",
                now,
                now,
                List.of(),
                List.of());
    }

    private TaskView task(
            String taskId,
            String taskKind,
            String status,
            List<TaskTargetView> targets) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 11, 0);
        return new TaskView(
                taskId,
                20L,
                "orders",
                "PAGE_ASSISTANT",
                taskKind,
                "v1",
                "CODEX",
                "处理订单列表",
                "完成订单列表页面接入",
                "READ_WRITE",
                status,
                "contract",
                "v1",
                "等待当前活动继续",
                "tester",
                now,
                null,
                "COMPLETED".equals(status) ? now : null,
                now,
                now,
                new ConnectionView(
                        "ACTIVE", null, "CODEX", "session",
                        null, now, now, null, null, null),
                targets,
                List.of());
    }

    private TaskTargetView pageTarget() {
        return new TaskTargetView(
                1L,
                "PAGE",
                "orders.list",
                "PRIMARY",
                "READ_ONLY",
                JsonNodeFactory.instance.objectNode());
    }

    private TaskTargetView workflowTarget(PublishedWorkflowView published) {
        return new TaskTargetView(
                2L,
                "WORKFLOW",
                published.workflowId(),
                "RELATED",
                "READ_ONLY",
                JsonNodeFactory.instance.objectNode()
                        .put("workflowVersionId", published.workflowVersionId()));
    }

    private PublishedWorkflowView published() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 12, 0);
        return new PublishedWorkflowView(
                "orders.list",
                "workflow-1",
                "orders-page-assistant",
                "订单页面助手",
                "帮助用户查询并维护订单",
                "PUBLISHED",
                101L,
                "v1.0.0",
                "tester",
                now,
                "agent-1",
                "orders-agent",
                "订单智能体",
                201L,
                1,
                "orders_page_assistant",
                "WRITE",
                "orders:write",
                0,
                null,
                null,
                null,
                null);
    }
}
