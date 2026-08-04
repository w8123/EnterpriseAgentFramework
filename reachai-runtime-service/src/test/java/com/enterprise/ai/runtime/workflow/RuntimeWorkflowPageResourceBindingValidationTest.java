package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeWorkflowPageResourceBindingValidationTest {

    @Test
    void blocksPageAssistantReleaseWithoutExactlyOneTargetPage() {
        RuntimeWorkflowResourceBindingService bindings =
                mock(RuntimeWorkflowResourceBindingService.class);
        when(bindings.list("wf-orders")).thenReturn(List.of());
        RuntimeWorkflowReleaseValidationService service = service(bindings);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow());

        assertFalse(result.valid());
        assertTrue(result.errors().stream()
                .anyMatch(item -> "PAGE_RESOURCE_BINDING_INVALID".equals(item.code())));
    }

    @Test
    void acceptsThePageBindingBoundaryWhenOneTargetPageExists() {
        RuntimeWorkflowResourceBindingService bindings =
                mock(RuntimeWorkflowResourceBindingService.class);
        when(bindings.list("wf-orders")).thenReturn(List.of(new BindingView(
                1L,
                "wf-orders",
                12L,
                "orders",
                "PAGE",
                "orders.detail",
                "TARGET",
                "ACTIVE",
                LocalDateTime.of(2026, 7, 25, 10, 0))));
        RuntimeWorkflowReleaseValidationService service = service(bindings);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow());

        assertTrue(result.errors().stream()
                .noneMatch(item -> item.code().startsWith("PAGE_RESOURCE_BINDING")));
    }

    @Test
    void rejectsPageActionsOutsideBindingsAndMissingCatalogArguments() {
        RuntimeWorkflowResourceBindingService bindings =
                mock(RuntimeWorkflowResourceBindingService.class);
        when(bindings.list("wf-orders")).thenReturn(List.of(new BindingView(
                1L,
                "wf-orders",
                12L,
                "orders",
                "PAGE",
                "orders.detail",
                "TARGET",
                "ACTIVE",
                LocalDateTime.of(2026, 7, 25, 10, 0))));
        RuntimeControlCatalogClient catalog = mock(RuntimeControlCatalogClient.class);
        when(catalog.getPageAction("orders", "orders.list", "cancel")).thenReturn(
                new RuntimeControlCatalogClient.PageActionCatalogEntry(
                        9L,
                        "orders",
                        "orders.list",
                        "cancel",
                        "取消订单",
                        null,
                        "WRITE",
                        true,
                        "orders.cancel",
                        Map.of("type", "object", "required", List.of("orderId")),
                        Map.of(),
                        Map.of(),
                        List.of(),
                        null,
                        Map.of(),
                        "ACTIVE"));
        RuntimeWorkflowReleaseValidationService service = new RuntimeWorkflowReleaseValidationService(
                catalog,
                new ObjectMapper(),
                new RuntimeWorkflowNodeCapabilityRegistry(),
                bindings);
        RuntimeWorkflowDefinitionEntity workflow = workflow();
        workflow.setGraphSpecJson("""
                {
                  "schemaVersion": 2,
                  "entryNodeId": "cancel",
                  "exitNodeIds": ["cancel"],
                  "nodes": [{
                    "id": "cancel",
                    "type": "PAGE_ACTION",
                    "config": {
                      "pageKey": "orders.list",
                      "actionKey": "cancel",
                      "args": {}
                    }
                  }],
                  "edges": []
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertTrue(result.errors().stream()
                .anyMatch(item -> "GRAPH_PAGE_ACTION_PAGE_UNBOUND".equals(item.code())));
        assertTrue(result.errors().stream()
                .anyMatch(item -> "GRAPH_PAGE_ACTION_ARGS_REQUIRED_MISSING".equals(item.code())));
        assertTrue(result.warnings().stream()
                .anyMatch(item -> "GRAPH_PAGE_ACTION_CONFIRM_REQUIRED".equals(item.code())));
    }

    private RuntimeWorkflowReleaseValidationService service(
            RuntimeWorkflowResourceBindingService bindings) {
        return new RuntimeWorkflowReleaseValidationService(
                mock(RuntimeControlCatalogClient.class),
                new ObjectMapper(),
                new RuntimeWorkflowNodeCapabilityRegistry(),
                bindings);
    }

    private RuntimeWorkflowDefinitionEntity workflow() {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-orders");
        workflow.setProjectId(12L);
        workflow.setProjectCode("orders");
        workflow.setWorkflowKind("PAGE_ASSISTANT");
        workflow.setExecutionEngine("GRAPH_SPEC");
        workflow.setGraphSpecJson("""
                {
                  "schemaVersion": 2,
                  "entryNodeId": "answer",
                  "exitNodeIds": ["answer"],
                  "nodes": [
                    {
                      "id": "answer",
                      "type": "ANSWER",
                      "config": {"template": "done"}
                    }
                  ],
                  "edges": []
                }
                """);
        return workflow;
    }
}
