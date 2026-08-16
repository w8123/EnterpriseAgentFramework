package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchWorkflowDeliveryService.DeliveryRequest;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchWorkflowDeliveryService.EngineeringDraftRequest;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchWorkflowDeliveryService.WorkflowInput;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService.ContextView;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService.CreateRequest;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService.ValidationView;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService.WorkflowSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimePageWorkbenchWorkflowDeliveryServiceTest {

    private RuntimeWorkflowAiCodingService workflowAiCodingService;
    private RuntimeWorkflowDefinitionService workflowDefinitionService;
    private RuntimeWorkflowResourceBindingService resourceBindingService;
    private RuntimeWorkflowVersionService workflowVersionService;
    private RuntimePageAssistantWorkflowAttachmentService attachmentService;
    private RuntimePageWorkbenchWorkflowDeliveryService service;

    @BeforeEach
    void setUp() {
        workflowAiCodingService = mock(RuntimeWorkflowAiCodingService.class);
        workflowDefinitionService =
                mock(RuntimeWorkflowDefinitionService.class);
        resourceBindingService =
                mock(RuntimeWorkflowResourceBindingService.class);
        workflowVersionService = mock(RuntimeWorkflowVersionService.class);
        attachmentService =
                mock(RuntimePageAssistantWorkflowAttachmentService.class);
        service = new RuntimePageWorkbenchWorkflowDeliveryService(
                workflowAiCodingService,
                workflowDefinitionService,
                resourceBindingService,
                workflowVersionService,
                attachmentService,
                new ObjectMapper());
    }

    @Test
    void artifactCreatesTaskScopedDraftWithOneExactTargetPageBinding() {
        when(workflowDefinitionService.findByKeySlug(
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        when(workflowAiCodingService.createWorkflow(
                org.mockito.ArgumentMatchers.any(CreateRequest.class)))
                .thenReturn(context());

        var result = service.createDraft(
                "orders",
                new EngineeringDraftRequest(
                        7L,
                        "orders",
                        "ait-orders-1",
                        "orders.detail",
                        "只读订单详情",
                        new WorkflowInput(
                                "订单详情页面助手",
                                "orders-detail-page-assistant",
                                "读取当前页面",
                                null,
                                new GraphSpec(),
                                null),
                        List.of("getPageState"),
                        List.of("src/views/orders/Detail.vue"),
                        List.of("只读取当前页面"),
                        List.of()));

        ArgumentCaptor<CreateRequest> captor =
                ArgumentCaptor.forClass(CreateRequest.class);
        verify(workflowAiCodingService).createWorkflow(captor.capture());
        CreateRequest request = captor.getValue();
        assertEquals(
                WorkflowSemanticValues.KIND_PAGE_ASSISTANT,
                request.workflowKind());
        assertEquals(1, request.resourceBindings().size());
        assertEquals(
                RuntimeWorkflowResourceBindingService.RESOURCE_PAGE,
                request.resourceBindings().get(0).resourceType());
        assertEquals(
                RuntimeWorkflowResourceBindingService.ROLE_TARGET,
                request.resourceBindings().get(0).bindingRole());
        assertEquals(
                "orders.detail",
                request.resourceBindings().get(0).resourceKey());
        assertTrue(request.keySlug().startsWith(
                "orders-detail-page-assistant-"));
        assertEquals("wf-orders-detail", result.workflow().id());
    }

    @Test
    void retryReusesTheTaskScopedDraftInsteadOfCreatingAnotherWorkflow() {
        RuntimeWorkflowDefinitionEntity existing = workflow();
        when(workflowDefinitionService.findByKeySlug(
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(workflowAiCodingService.createWorkflow(
                org.mockito.ArgumentMatchers.any(CreateRequest.class)))
                .thenReturn(context());
        when(resourceBindingService.list("wf-orders-detail"))
                .thenReturn(List.of(new BindingView(
                        1L,
                        "wf-orders-detail",
                        7L,
                        "orders",
                        RuntimeWorkflowResourceBindingService.RESOURCE_PAGE,
                        "orders.detail",
                        RuntimeWorkflowResourceBindingService.ROLE_TARGET,
                        "ACTIVE",
                        LocalDateTime.of(2026, 7, 26, 10, 0))));
        when(workflowAiCodingService.replaceDraft(
                org.mockito.ArgumentMatchers.eq("wf-orders-detail"),
                org.mockito.ArgumentMatchers.any(CreateRequest.class)))
                .thenReturn(context());

        EngineeringDraftRequest request = new EngineeringDraftRequest(
                7L,
                "orders",
                "ait-orders-1",
                "orders.detail",
                "Read-only order detail assistant",
                new WorkflowInput(
                        "Order detail page assistant",
                        "orders-detail-page-assistant",
                        "Read the current page",
                        null,
                        new GraphSpec(),
                        null),
                List.of("getPageState"),
                List.of("src/views/orders/Detail.vue"),
                List.of("Only read the current page"),
                List.of());

        service.createDraft("orders", request);
        var retried = service.createDraft("orders", request);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(workflowDefinitionService, times(2))
                .findByKeySlug(keys.capture());
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
        assertEquals("wf-orders-detail", retried.workflow().id());
        verify(workflowAiCodingService, times(1)).createWorkflow(
                org.mockito.ArgumentMatchers.any(CreateRequest.class));
        ArgumentCaptor<CreateRequest> replaceCaptor =
                ArgumentCaptor.forClass(CreateRequest.class);
        verify(workflowAiCodingService).replaceDraft(
                org.mockito.ArgumentMatchers.eq("wf-orders-detail"),
                replaceCaptor.capture());
        assertEquals(
                "Order detail page assistant",
                replaceCaptor.getValue().name());
    }

    @Test
    void retryWithChangedGraphSpecOverwritesTheExistingDraft() {
        RuntimeWorkflowDefinitionEntity existing = workflow();
        GraphSpec corrected = new GraphSpec();
        corrected.setEntryNodeId("answer");
        when(workflowDefinitionService.findByKeySlug(
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(existing));
        when(resourceBindingService.list("wf-orders-detail"))
                .thenReturn(List.of(new BindingView(
                        1L,
                        "wf-orders-detail",
                        7L,
                        "orders",
                        RuntimeWorkflowResourceBindingService.RESOURCE_PAGE,
                        "orders.detail",
                        RuntimeWorkflowResourceBindingService.ROLE_TARGET,
                        "ACTIVE",
                        LocalDateTime.of(2026, 7, 26, 10, 0))));
        when(workflowAiCodingService.replaceDraft(
                org.mockito.ArgumentMatchers.eq("wf-orders-detail"),
                org.mockito.ArgumentMatchers.any(CreateRequest.class)))
                .thenReturn(context());

        service.createDraft(
                "orders",
                new EngineeringDraftRequest(
                        7L,
                        "orders",
                        "ait-orders-1",
                        "orders.detail",
                        "Corrected draft after Control rollback",
                        new WorkflowInput(
                                "Order detail page assistant",
                                "orders-detail-page-assistant",
                                "Read the current page",
                                null,
                                corrected,
                                Map.of("version", 2)),
                        List.of("getPageState"),
                        List.of("src/views/orders/Detail.vue"),
                        List.of("Only read the current page"),
                        List.of()));

        ArgumentCaptor<CreateRequest> replaceCaptor =
                ArgumentCaptor.forClass(CreateRequest.class);
        verify(workflowAiCodingService, times(0)).createWorkflow(
                org.mockito.ArgumentMatchers.any(CreateRequest.class));
        verify(workflowAiCodingService).replaceDraft(
                org.mockito.ArgumentMatchers.eq("wf-orders-detail"),
                replaceCaptor.capture());
        assertEquals("answer", replaceCaptor.getValue().graphSpec().getEntryNodeId());
        assertEquals(Map.of("version", 2), replaceCaptor.getValue().canvas());
    }

    @Test
    void acceptedDeliveryPublishesBeforeAttachingToPageCopilot() {
        RuntimeWorkflowDefinitionEntity workflow = workflow();
        when(workflowDefinitionService.findById("wf-orders-detail"))
                .thenReturn(Optional.of(workflow));
        when(resourceBindingService.list("wf-orders-detail"))
                .thenReturn(List.of(new BindingView(
                        1L,
                        "wf-orders-detail",
                        7L,
                        "orders",
                        RuntimeWorkflowResourceBindingService.RESOURCE_PAGE,
                        "orders.detail",
                        RuntimeWorkflowResourceBindingService.ROLE_TARGET,
                        "ACTIVE",
                        LocalDateTime.of(2026, 7, 26, 10, 0))));
        when(workflowVersionService.validateRelease("wf-orders-detail"))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder()
                        .build());
        when(workflowVersionService.listVersions("wf-orders-detail"))
                .thenReturn(List.of());
        RuntimeWorkflowVersionEntity version =
                new RuntimeWorkflowVersionEntity();
        version.setId(31L);
        version.setWorkflowId("wf-orders-detail");
        version.setVersion("v1.0.0");
        version.setStatus("ACTIVE");
        version.setPublishedAt(
                LocalDateTime.of(2026, 7, 26, 10, 5));
        when(workflowVersionService.publish(
                "wf-orders-detail",
                "v1.0.0",
                100,
                "Delivered from Page Workbench task ait-orders-1",
                "tester")).thenReturn(version);
        when(attachmentService.attachPublishedPageWorkflow(
                org.mockito.ArgumentMatchers.eq("wf-orders-detail"),
                org.mockito.ArgumentMatchers.any(
                        RuntimePageAssistantWorkflowAttachRequest.class)))
                .thenReturn(new RuntimePageAssistantWorkflowAttachment(
                        "agent-orders",
                        "orders-page-copilot",
                        "wf-orders-detail",
                        "orders-detail-page-assistant",
                        "orders_detail_page_assistant",
                        41L,
                        2,
                        "ACTIVE",
                        true));

        var result = service.deliver(
                "orders",
                "wf-orders-detail",
                new DeliveryRequest(
                        7L,
                        "orders",
                        "ait-orders-1",
                        "orders.detail",
                        "v1.0.0",
                        null,
                        null,
                        "tester"));

        InOrder writes = inOrder(
                workflowVersionService,
                attachmentService);
        writes.verify(workflowVersionService).publish(
                "wf-orders-detail",
                "v1.0.0",
                100,
                "Delivered from Page Workbench task ait-orders-1",
                "tester");
        writes.verify(attachmentService)
                .attachPublishedPageWorkflow(
                        org.mockito.ArgumentMatchers.eq(
                                "wf-orders-detail"),
                        org.mockito.ArgumentMatchers.any(
                                RuntimePageAssistantWorkflowAttachRequest.class));
        assertTrue(result.published());
        assertEquals(31L, result.workflowVersionId());
        assertEquals("agent-orders", result.agentId());
    }

    @Test
    void acceptedDeliveryCanExplicitlyReplaceAWorkflowBoundToTheSamePage() {
        RuntimeWorkflowDefinitionEntity workflow = workflow();
        RuntimeWorkflowDefinitionEntity replaced = new RuntimeWorkflowDefinitionEntity();
        replaced.setId("wf-orders-old");
        replaced.setProjectId(7L);
        replaced.setProjectCode("orders");
        replaced.setWorkflowKind(WorkflowSemanticValues.KIND_PAGE_ASSISTANT);
        replaced.setStatus("ACTIVE");
        when(workflowDefinitionService.findById("wf-orders-detail"))
                .thenReturn(Optional.of(workflow));
        when(workflowDefinitionService.findById("wf-orders-old"))
                .thenReturn(Optional.of(replaced));
        BindingView pageBinding = new BindingView(
                1L, "wf-orders-detail", 7L, "orders",
                RuntimeWorkflowResourceBindingService.RESOURCE_PAGE,
                "orders.detail",
                RuntimeWorkflowResourceBindingService.ROLE_TARGET,
                "ACTIVE",
                LocalDateTime.of(2026, 7, 26, 10, 0));
        when(resourceBindingService.list("wf-orders-detail"))
                .thenReturn(List.of(pageBinding));
        when(resourceBindingService.list("wf-orders-old"))
                .thenReturn(List.of(new BindingView(
                        2L, "wf-orders-old", 7L, "orders",
                        RuntimeWorkflowResourceBindingService.RESOURCE_PAGE,
                        "orders.detail",
                        RuntimeWorkflowResourceBindingService.ROLE_TARGET,
                        "ACTIVE",
                        LocalDateTime.of(2026, 7, 26, 9, 0))));
        when(workflowVersionService.validateRelease("wf-orders-detail"))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId(31L);
        version.setWorkflowId("wf-orders-detail");
        version.setVersion("v1.0.0");
        version.setStatus("ACTIVE");
        when(workflowVersionService.listVersions("wf-orders-detail"))
                .thenReturn(List.of(version));
        when(attachmentService.attachPublishedPageWorkflow(
                eq("wf-orders-detail"),
                any(RuntimePageAssistantWorkflowAttachRequest.class)))
                .thenReturn(new RuntimePageAssistantWorkflowAttachment(
                        "agent-orders", "orders-page-copilot",
                        "wf-orders-detail", "orders-detail-page-assistant",
                        "orders_detail_page_assistant", 41L, 2, "ACTIVE",
                        true, "wf-orders-old"));

        var result = service.deliver(
                "orders",
                "wf-orders-detail",
                new DeliveryRequest(
                        7L, "orders", "ait-orders-1", "orders.detail",
                        "v1.0.0", null, null, "tester", "wf-orders-old"));

        ArgumentCaptor<RuntimePageAssistantWorkflowAttachRequest> request =
                ArgumentCaptor.forClass(RuntimePageAssistantWorkflowAttachRequest.class);
        verify(attachmentService).attachPublishedPageWorkflow(
                eq("wf-orders-detail"), request.capture());
        assertEquals("wf-orders-old", request.getValue().replaceWorkflowId());
        assertEquals("wf-orders-old", result.replacedWorkflowId());
    }

    private ContextView context() {
        return new ContextView(
                new WorkflowSnapshot(
                        "wf-orders-detail",
                        "orders-detail-page-assistant-1234567890",
                        "订单详情页面助手",
                        "读取当前页面",
                        7L,
                        "orders",
                        WorkflowSemanticValues.KIND_PAGE_ASSISTANT,
                        WorkflowSemanticValues.ENGINE_GRAPH_SPEC,
                        "STUDIO",
                        "AI_CODING",
                        null,
                        "DRAFT",
                        LocalDateTime.of(2026, 7, 26, 10, 1)),
                new GraphSpec(),
                Map.of(),
                new ValidationView(
                        "wf-orders-detail",
                        "CURRENT",
                        true,
                        List.of(),
                        List.of()),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private RuntimeWorkflowDefinitionEntity workflow() {
        RuntimeWorkflowDefinitionEntity workflow =
                new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-orders-detail");
        workflow.setProjectId(7L);
        workflow.setProjectCode("orders");
        workflow.setKeySlug("orders-detail-page-assistant-1234567890");
        workflow.setName("订单详情页面助手");
        workflow.setWorkflowKind(
                WorkflowSemanticValues.KIND_PAGE_ASSISTANT);
        workflow.setExecutionEngine(
                WorkflowSemanticValues.ENGINE_GRAPH_SPEC);
        workflow.setStatus("DRAFT");
        workflow.setExtraJson("""
                {
                  "source": "PAGE_WORKBENCH",
                  "taskId": "ait-orders-1",
                  "pageKey": "orders.detail"
                }
                """);
        return workflow;
    }
}
