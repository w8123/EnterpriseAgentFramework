package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDetailView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowDeliveryView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchWorkflowDeliveryApplicationService.DeliveryCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageWorkbenchWorkflowDeliveryApplicationServiceTest {

    @Test
    void acceptedTaskDeliversItsLatestAppliedWorkflowDraft() {
        AiCodingTaskApplicationService taskService =
                mock(AiCodingTaskApplicationService.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        when(taskService.detail("ait-orders-1"))
                .thenReturn(taskDetail());
        WorkflowDeliveryView delivery = new WorkflowDeliveryView(
                "reachai.page-workbench.workflow-delivery.v1",
                "ait-orders-1",
                "orders.detail",
                "wf-new",
                "orders-page-assistant-new",
                "订单页面助手",
                31L,
                "v1.0.0",
                LocalDateTime.of(2026, 7, 26, 10, 5),
                "agent-orders",
                "orders-page-copilot",
                41L,
                2,
                "orders_page_assistant",
                "ACTIVE",
                true);
        when(runtimeClient.deliverPageWorkbenchWorkflow(
                org.mockito.ArgumentMatchers.eq("orders"),
                org.mockito.ArgumentMatchers.eq("wf-new"),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(ResponseEntity.ok(delivery));
        PageWorkbenchWorkflowDeliveryApplicationService service =
                new PageWorkbenchWorkflowDeliveryApplicationService(
                        taskService,
                        runtimeClient);

        WorkflowDeliveryView result = service.deliver(
                "orders",
                "ait-orders-1",
                new DeliveryCommand(
                        "orders.detail",
                        null,
                        null,
                        null,
                        "tester"));

        ArgumentCaptor<Map<String, Object>> request =
                ArgumentCaptor.forClass(Map.class);
        verify(runtimeClient).deliverPageWorkbenchWorkflow(
                org.mockito.ArgumentMatchers.eq("orders"),
                org.mockito.ArgumentMatchers.eq("wf-new"),
                request.capture());
        assertEquals("v1.0.0", request.getValue().get("version"));
        assertEquals("orders.detail", result.pageKey());
    }

    private TaskDetailView taskDetail() {
        TaskView task = new TaskView(
                "ait-orders-1",
                7L,
                "orders",
                "BUSINESS_PAGE_WORKBENCH",
                PageWorkbenchTaskProvider.WORKFLOW_ENGINEERING,
                "v1",
                "TRAE",
                "Workflow 工程",
                "建立页面助手",
                "READ_ONLY",
                "COMPLETED",
                "reachai.workflow-engineering-report",
                "v1",
                "accepted",
                "tester",
                LocalDateTime.of(2026, 7, 26, 10, 0),
                LocalDateTime.of(2026, 7, 26, 10, 2),
                LocalDateTime.of(2026, 7, 26, 10, 3),
                LocalDateTime.of(2026, 7, 26, 9, 55),
                LocalDateTime.of(2026, 7, 26, 10, 3),
                null,
                List.of(new TaskTargetView(
                        1L,
                        "PAGE",
                        "orders.detail",
                        "PRIMARY",
                        "READ_ONLY",
                        new ObjectMapper().createObjectNode())),
                List.of());
        return new TaskDetailView(
                task,
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        artifact(1L, "wf-old"),
                        artifact(2L, "wf-new")));
    }

    private ArtifactView artifact(Long id, String workflowId) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode engineering = mapper.createObjectNode();
        engineering.put("schema",
                "reachai.page-workbench.workflow-engineering-draft.v1");
        engineering.put("taskId", "ait-orders-1");
        engineering.put("pageKey", "orders.detail");
        engineering.withObject("workflow").put("id", workflowId);
        ObjectNode applicationResult = mapper.createObjectNode();
        applicationResult.set("workflowEngineering", engineering);
        return new ArtifactView(
                id,
                "artifact-" + id,
                "reachai.workflow-engineering-report",
                "v1",
                "hash-" + id,
                "APPLIED",
                "ok",
                applicationResult,
                "TRAE",
                LocalDateTime.of(2026, 7, 26, 10, id.intValue()),
                LocalDateTime.of(2026, 7, 26, 10, id.intValue()),
                LocalDateTime.of(2026, 7, 26, 10, id.intValue()));
    }
}
