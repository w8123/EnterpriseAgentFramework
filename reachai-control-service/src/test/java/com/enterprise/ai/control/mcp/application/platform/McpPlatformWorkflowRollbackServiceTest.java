package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowRollbackPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformWorkflowRollbackServiceTest {
    private static final String REV = "2026-10-30T10:00:00";
    private static final String INVOCATION = "00000000-0000-0000-0000-000000000002";
    private final McpPlatformReadPort reads = mock(McpPlatformReadPort.class);
    private final McpPlatformWorkflowRollbackPort rollbacks = mock(McpPlatformWorkflowRollbackPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformWorkflowRollbackService service = new McpPlatformWorkflowRollbackService(reads, rollbacks, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "dev", "开发"), "session", null, List.of(), List.of(), List.of()));

    @Test void requestFreezesTargetGraphLiveVersionAndRevisionWithoutRollingBack() {
        arrangeState(REV, 9L, "{\"nodes\":[1]}");
        service.request(actor, "wf-1", 8L, REV, INVOCATION);
        @SuppressWarnings("unchecked") var capture = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("WORKFLOW_ROLLBACK"), eq("WORKFLOW:wf-1"), capture.capture());
        assertEquals(8, ((Number) capture.getValue().get("versionId")).intValue());
        assertEquals(9, ((Number) capture.getValue().get("activeVersionId")).intValue());
        assertEquals(64, String.valueOf(capture.getValue().get("targetGraphSha256")).length());
        verifyNoInteractions(rollbacks);
    }

    @Test void requestRefusesStaleRevisionOrTheCurrentLiveVersion() {
        arrangeState("2026-10-30T11:00:00", 9L, "{}");
        assertEquals(409, service.request(actor, "wf-1", 8L, REV, INVOCATION).getStatusCode().value());
        arrangeState(REV, 9L, "{}");
        assertEquals(409, service.request(actor, "wf-1", 9L, REV, INVOCATION).getStatusCode().value());
        assertEquals(404, service.request(actor, "wf-1", 77L, REV, INVOCATION).getStatusCode().value());
        verifyNoInteractions(approvals, rollbacks);
    }

    @Test void executionRefusesWhenTheLiveVersionMovedAndLostResultsAreUnknown() {
        Map<String, Object> frozen = approvedSnapshot();
        arrangeState(REV, 10L, "{\"nodes\":[1]}");
        when(approvals.claim(actor, "approval", "WORKFLOW_ROLLBACK")).thenReturn(new PlatformAiApprovalService.Claim("approval", "WORKFLOW_ROLLBACK", "WORKFLOW:wf-1", frozen));
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verifyNoInteractions(rollbacks);

        reset(approvals); arrangeState(REV, 9L, "{\"nodes\":[1]}");
        when(approvals.claim(actor, "approval", "WORKFLOW_ROLLBACK")).thenReturn(new PlatformAiApprovalService.Claim("approval", "WORKFLOW_ROLLBACK", "WORKFLOW:wf-1", frozen));
        doReturn(ResponseEntity.ok(Map.of("id", 8, "workflowId", "wf-1", "status", "ACTIVE"))).when(rollbacks).rollback(actor.session(), "wf-1", 8L, REV);
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);

        reset(approvals, rollbacks); arrangeState(REV, 9L, "{\"nodes\":[1]}");
        when(approvals.claim(actor, "approval", "WORKFLOW_ROLLBACK")).thenReturn(new PlatformAiApprovalService.Claim("approval", "WORKFLOW_ROLLBACK", "WORKFLOW:wf-1", frozen));
        when(rollbacks.rollback(any(), any(), anyLong(), any())).thenThrow(new IllegalStateException("private transport detail"));
        var unknown = service.execute(actor, "approval");
        assertEquals(202, unknown.getStatusCode().value());
        assertFalse(String.valueOf(unknown.getBody()).contains("private"));
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    private Map<String, Object> approvedSnapshot() {
        arrangeState(REV, 9L, "{\"nodes\":[1]}");
        service.request(actor, "wf-1", 8L, REV, INVOCATION);
        @SuppressWarnings("unchecked") var capture = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(any(), any(), any(), capture.capture());
        @SuppressWarnings("unchecked") Map<String, Object> frozen = capture.getValue();
        return frozen;
    }

    private void arrangeState(String revision, long activeId, String targetGraph) {
        doReturn(ResponseEntity.ok(Map.of("id", "wf-1", "name", "订单查询", "projectId", 7, "projectCode", "orders", "revision", revision)))
                .when(reads).getWorkflowWorkingCopy(actor.session(), "wf-1");
        doReturn(ResponseEntity.ok(List.of(
                Map.of("id", activeId, "workflowId", "wf-1", "version", "v3", "status", "ACTIVE", "graphSpecSnapshotJson", "{}"),
                Map.of("id", 8, "workflowId", "wf-1", "version", "v2", "status", "ARCHIVED", "graphSpecSnapshotJson", targetGraph))))
                .when(reads).listWorkflowVersions(actor.session(), "wf-1");
    }
}
