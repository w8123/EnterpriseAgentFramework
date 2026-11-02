package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowReleasePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformWorkflowReleaseServiceTest {
    private final McpPlatformWorkflowReleasePort port = mock(McpPlatformWorkflowReleasePort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformWorkflowReleaseService service = new McpPlatformWorkflowReleaseService(port, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(), List.of()));
    private static final String REV = "2026-10-29T10:00:00";
    @Test void preparationFreezesServerDefinitionAndCannotPublish() {
        when(port.context(actor.session(), "wf-1", REV, "v1", null)).thenReturn(ResponseEntity.ok(snapshot()));
        service.request(actor, "wf-1", REV, "v1", null, "00000000-0000-0000-0000-000000000001");
        @SuppressWarnings("unchecked") var capture = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("WORKFLOW_PUBLISH"), eq("WORKFLOW:wf-1"), capture.capture());
        assertEquals("{}", capture.getValue().get("publishedGraph"));
        assertEquals("a".repeat(64), capture.getValue().get("publicationDigest"));
        verify(port, never()).publish(any(), any(), any(), any(), any(), any(), any(), any());
    }
    @Test void driftRefusesTheClaimBeforeDispatch() {
        arrange(); var changed = snapshot(); changed.put("publicationDigest", "b".repeat(64));
        when(port.context(actor.session(), "wf-1", REV, "v1", null)).thenReturn(ResponseEntity.ok(changed));
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verify(port, never()).publish(any(), any(), any(), any(), any(), any(), any(), any());
    }
    @Test void successUsesOnlyFrozenArgumentsAndLostResponsesAreUnknown() {
        arrange();
        when(port.publish(actor.session(), "wf-1", REV, "v1", null, 7L, "orders", "a".repeat(64)))
                .thenReturn(ResponseEntity.ok(Map.of("id", 8, "workflowId", "wf-1", "version", "v1", "status", "ACTIVE", "graphSpecSnapshotJson", "{}")));
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
        reset(port, approvals); arrange();
        when(port.publish(any(), any(), any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("private transport detail"));
        var result = service.execute(actor, "approval");
        assertEquals(202, result.getStatusCode().value());
        assertFalse(String.valueOf(result.getBody()).contains("private"));
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        verify(port, times(1)).publish(any(), any(), any(), any(), any(), any(), any(), any());
    }
    private void arrange() {
        when(approvals.claim(actor, "approval", "WORKFLOW_PUBLISH")).thenReturn(new PlatformAiApprovalService.Claim("approval", "WORKFLOW_PUBLISH", "WORKFLOW:wf-1", snapshot()));
        when(port.context(actor.session(), "wf-1", REV, "v1", null)).thenReturn(ResponseEntity.ok(snapshot()));
    }
    private static Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("contractVersion", 1); result.put("workflowId", "wf-1"); result.put("projectId", 7L); result.put("projectCode", "orders");
        result.put("baseRevision", REV); result.put("version", "v1"); result.put("note", null); result.put("activeVersionId", null);
        result.put("definition", Map.of("id", "wf-1", "projectId", 7L, "projectCode", "orders", "name", "订单查询"));
        result.put("publishedGraph", "{}"); result.put("publicationDigest", "a".repeat(64));
        return result;
    }
}
