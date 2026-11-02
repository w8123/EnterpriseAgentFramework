package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPageWorkflowDeliveryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformPageWorkflowDeliveryServiceTest {
    private final McpPlatformPageWorkflowDeliveryPort deliveries = mock(McpPlatformPageWorkflowDeliveryPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformPageWorkflowDeliveryService service = new McpPlatformPageWorkflowDeliveryService(deliveries, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(),
                    List.of("workflow:publish"), List.of()));

    @Test void deliveryIsReviewedAndRefusedWhenANewerEngineeringResultWasApplied() {
        plan(5L);
        service.request(actor, "orders", "task-1", Map.of("agentId", "agent-1"), "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("PAGE_WORKFLOW_DELIVERY"), eq("PAGE_WORKFLOW_TASK:task-1"), frozen.capture());
        verify(deliveries, never()).deliver(any(), any(), any(), any());
        when(approvals.claim(actor, "approval", "PAGE_WORKFLOW_DELIVERY"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "PAGE_WORKFLOW_DELIVERY", "PAGE_WORKFLOW_TASK:task-1", frozen.getValue()));

        plan(6L);
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(deliveries, never()).deliver(any(), any(), any(), any());

        reset(approvals);
        when(approvals.claim(actor, "approval", "PAGE_WORKFLOW_DELIVERY"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "PAGE_WORKFLOW_DELIVERY", "PAGE_WORKFLOW_TASK:task-1", frozen.getValue()));
        plan(5L);
        doReturn(ResponseEntity.ok(Map.of("published", true))).when(deliveries).deliver(eq(actor.session()), eq("orders"), eq("task-1"), any());
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(deliveries, times(1)).deliver(eq(actor.session()), eq("orders"), eq("task-1"), any());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    @Test void unconfirmedRuntimeDeliveryIsUnknown() {
        plan(5L);
        Map<String, Object> frozen = new LinkedHashMap<>();
        service.request(actor, "orders", "task-1", Map.of(), "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> captured = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(any(), any(), any(), captured.capture());
        frozen.putAll(captured.getValue());
        when(approvals.claim(actor, "approval", "PAGE_WORKFLOW_DELIVERY"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "PAGE_WORKFLOW_DELIVERY", "PAGE_WORKFLOW_TASK:task-1", frozen));
        when(deliveries.deliver(any(), any(), any(), any())).thenThrow(new IllegalStateException("Runtime did not confirm Workflow delivery"));
        assertEquals(202, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    @Test void notAcceptedTaskIsRefusedBeforeReview() {
        when(deliveries.plan(any(), any(), any(), any())).thenThrow(new IllegalStateException("Workflow engineering task must be accepted before delivery"));
        assertEquals(409, service.request(actor, "orders", "task-1", Map.of(), "inv").getStatusCode().value());
        verifyNoInteractions(approvals);
    }

    private void plan(long artifactId) {
        doReturn(new LinkedHashMap<>(Map.of("workflowId", "wf-9", "artifactId", artifactId,
                "request", Map.of("projectCode", "orders", "taskId", "task-1", "pageKey", "order-list", "version", "v1.0.0", "publishedBy", "operator"))))
                .when(deliveries).plan(eq(actor.session()), eq("orders"), eq("task-1"), any());
    }
}
