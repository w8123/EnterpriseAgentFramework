package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPageAssistantSyncPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformPageAssistantSyncServiceTest {
    private static final String REV = "c".repeat(64);
    private static final String INVOCATION = "00000000-0000-0000-0000-000000000001";
    private final McpPlatformPageAssistantSyncPort port = mock(McpPlatformPageAssistantSyncPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final McpPlatformPageAssistantSyncService service = new McpPlatformPageAssistantSyncService(port, approvals, json);
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "dev", "开发"), "session", null, List.of(), List.of(), List.of()));

    @Test void requestFreezesTheReadTargetAndNeverAttaches() {
        doReturn(ResponseEntity.ok(context(true, REV))).when(port).context(actor.session(), "wf-1");
        service.request(actor, "wf-1", REV, false, null, null, INVOCATION);
        @SuppressWarnings("unchecked") var capture = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("PAGE_ASSISTANT_SYNC"), eq("WORKFLOW:wf-1"), capture.capture());
        assertEquals(REV, capture.getValue().get("baseRevision"));
        assertEquals("agent-1", capture.getValue().get("agentId"));
        assertEquals(INVOCATION, capture.getValue().get("invocationId"));
        verify(port, never()).attach(any(), any(), any());
    }

    @Test void requestRefusesAStaleRevisionOrAWrongInitializationChoice() {
        doReturn(ResponseEntity.ok(context(true, REV))).when(port).context(actor.session(), "wf-1");
        assertEquals(409, service.request(actor, "wf-1", "d".repeat(64), false, null, null, INVOCATION).getStatusCode().value());
        assertEquals(409, service.request(actor, "wf-1", null, true, null, null, INVOCATION).getStatusCode().value());
        doReturn(ResponseEntity.ok(context(false, null))).when(port).context(actor.session(), "wf-1");
        assertEquals(409, service.request(actor, "wf-1", REV, false, null, null, INVOCATION).getStatusCode().value());
        verifyNoInteractions(approvals);
    }

    @Test void executionRefusesWhenTheTargetChangedAfterApproval() {
        arrange();
        doReturn(ResponseEntity.ok(context(true, "e".repeat(64)))).when(port).context(actor.session(), "wf-1");
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verify(port, never()).attach(any(), any(), any());
    }

    @Test void executionSendsOnlyTheFrozenRevisionAndLostResultsAreUnknown() {
        arrange();
        doReturn(ResponseEntity.ok(Map.of("workflowId", "wf-1", "agentId", "agent-1", "published", true))).when(port).attach(actor.session(), "wf-1", Map.of("baseRevision", REV));
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);

        reset(port, approvals); arrange();
        when(port.attach(any(), any(), any())).thenThrow(new IllegalStateException("private transport detail"));
        var result = service.execute(actor, "approval");
        assertEquals(202, result.getStatusCode().value());
        assertFalse(String.valueOf(result.getBody()).contains("private"));
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        verify(port, times(1)).attach(any(), any(), any());
    }

    private void arrange() {
        doReturn(ResponseEntity.ok(context(true, REV))).when(port).context(actor.session(), "wf-1");
        service.request(actor, "wf-1", REV, false, null, null, INVOCATION);
        @SuppressWarnings("unchecked") var capture = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(any(), any(), any(), capture.capture());
        @SuppressWarnings("unchecked") Map<String, Object> frozen = capture.getValue();
        clearInvocations(approvals);
        when(approvals.claim(actor, "approval", "PAGE_ASSISTANT_SYNC"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "PAGE_ASSISTANT_SYNC", "WORKFLOW:wf-1", frozen));
    }

    private static Map<String, Object> context(boolean exists, String revision) {
        var result = new LinkedHashMap<String, Object>();
        result.put("contractVersion", 1); result.put("projectId", 7); result.put("projectCode", "orders");
        result.put("workflowId", "wf-1"); result.put("workflowKeySlug", "orders-page"); result.put("workflowName", "订单页面助手");
        result.put("workflowStatus", "ACTIVE"); result.put("workflowActiveVersionId", 11); result.put("targetExists", exists);
        result.put("agentId", exists ? "agent-1" : null); result.put("agentKeySlug", exists ? "orders-page-copilot" : null);
        result.put("revision", revision); result.put("activeConfigId", exists ? 21 : null); result.put("activeConfigVersionNo", exists ? 2 : null);
        result.put("activeModelInstanceId", null); result.put("attached", false); result.put("attachedWorkflowVersionId", null);
        return result;
    }
}
