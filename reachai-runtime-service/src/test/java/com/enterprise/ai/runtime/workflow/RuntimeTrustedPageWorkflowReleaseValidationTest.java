package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeTrustedPageWorkflowReleaseValidationTest {
    @Test void markedDraftMustMatchLiveReviewButLegacyDoesNotRequireReview() {
        var bindings = mock(RuntimeWorkflowResourceBindingService.class);
        when(bindings.list("wf")).thenReturn(List.of(new RuntimeWorkflowResourceBindingService.BindingView(
                1L, "wf", 1L, "p", "PAGE", "page", "TARGET", "ACTIVE", LocalDateTime.now())));
        var control = mock(RuntimeControlCatalogClient.class);
        var schema = Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")), "additionalProperties", false);
        when(control.getPageAction("p", "page", "fill")).thenReturn(new RuntimeControlCatalogClient.PageActionCatalogEntry(
                6L, "p", "page", "fill", "填写", null, "WRITE", true, "page.fill", schema, Map.of(), Map.of(), List.of(), null, Map.of(), "ACTIVE"));
        when(control.resolvePageEffect(any())).thenReturn(new RuntimeControlCatalogClient.TrustedPageEffectApproval(
                3L, 4L, 5L, 6L, "p", "page", "fill", "PAGE_LOCAL_DRAFT", "a".repeat(64), "v1", "资料", "填写资料", null,
                "/page", schema, Map.of("name", "姓名"), true));
        var service = new RuntimeWorkflowReleaseValidationService(control, new ObjectMapper(), new RuntimeWorkflowNodeCapabilityRegistry(), bindings);
        var workflow = new RuntimeWorkflowDefinitionEntity(); workflow.setId("wf"); workflow.setProjectId(1L);
        workflow.setProjectCode("p"); workflow.setWorkflowKind("PAGE_ASSISTANT"); workflow.setExecutionEngine("GRAPH_SPEC");
        String graph = "{\"schemaVersion\":2,\"env\":{\"reachaiTrustedPageDraft\":true},\"entryNodeId\":\"input\",\"exitNodeIds\":[\"fill\"],"
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"additionalProperties\":false},"
                + "\"nodes\":[{\"id\":\"input\",\"type\":\"USER_INPUT\",\"config\":{\"outputAlias\":\"params\","
                + "\"fields\":[{\"name\":\"name\",\"type\":\"string\",\"required\":false,\"source\":\"input.name\"}]}},"
                + "{\"id\":\"fill\",\"type\":\"PAGE_ACTION\",\"config\":{\"projectCode\":\"p\",\"pageKey\":\"page\",\"actionKey\":\"fill\","
                + "\"route\":\"/page\",\"inputMapping\":{\"name\":\"params.name\"}}}],\"edges\":[{\"from\":\"input\",\"to\":\"fill\"}]}";
        workflow.setGraphSpecJson(graph);
        assertTrue(service.validate(workflow).valid(), () -> service.validate(workflow).errors().toString());
        workflow.setGraphSpecJson(graph.replace("/page", "/different"));
        assertTrue(service.validate(workflow).errors().stream().anyMatch(item -> "PAGE_DRAFT_REVIEW_INVALID".equals(item.code())));
        workflow.setGraphSpecJson(graph);
        when(control.resolvePageEffect(any())).thenThrow(new IllegalStateException("Control unavailable"));
        assertTrue(service.validate(workflow).errors().stream().anyMatch(item -> "PAGE_DRAFT_REVIEW_INVALID".equals(item.code())));
        reset(control);
        when(control.getPageAction("p", "page", "fill")).thenReturn(new RuntimeControlCatalogClient.PageActionCatalogEntry(
                6L, "p", "page", "fill", "填写", null, "WRITE", true, "page.fill", schema, Map.of(), Map.of(), List.of(), null, Map.of(), "ACTIVE"));
        workflow.setGraphSpecJson(graph.replace("\"env\":{\"reachaiTrustedPageDraft\":true},", ""));
        assertTrue(service.validate(workflow).valid());
        verify(control, never()).resolvePageEffect(any());
    }
}
