package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAgentWorkflowPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformReadPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformAgentWorkflowServiceTest {
    private final McpPlatformReadPort reads = mock(McpPlatformReadPort.class);
    private final McpPlatformAgentWorkflowPort agents = mock(McpPlatformAgentWorkflowPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformAgentWorkflowService service = new McpPlatformAgentWorkflowService(reads, agents, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(),
                    List.of("agent:publish"), List.of()));

    @Test void publishLatestFreezesThePinsAndSendsTheReviewedActiveConfigOnce() {
        stub(7, 12L);
        service.request(actor, McpPlatformAgentWorkflowService.Operation.PUBLISH_LATEST, "agent-1", null, null, null, "inv");
        Map<String, Object> frozen = frozen();
        doReturn(ResponseEntity.ok(Map.of("agentId", "agent-1"))).when(agents).publishLatest(actor.session(), "agent-1", 7L);
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(agents, times(1)).publishLatest(actor.session(), "agent-1", 7L);
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
        assertEquals(7, ((Number) frozen.get("activeConfigVersionId")).intValue());
    }

    @Test void aNewerWorkflowVersionSinceReviewRefusesExecution() {
        stub(7, 12L);
        service.request(actor, McpPlatformAgentWorkflowService.Operation.PUBLISH_LATEST, "agent-1", null, null, null, "inv");
        frozen();
        stub(7, 13L);
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(agents, never()).publishLatest(any(), anyString(), anyLong());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
    }

    @Test void knowledgeQaAttachIsUnknownWhenTheResultIsLost() {
        stub(7, 12L);
        service.request(actor, McpPlatformAgentWorkflowService.Operation.KNOWLEDGE_QA, "agent-1", "kb_faq", "FAQ", null, "inv");
        frozen();
        when(agents.knowledgeQa(any(), anyString(), anyBoolean(), any(), any(), any())).thenThrow(new IllegalStateException("timeout"));
        assertEquals(202, service.execute(actor, "approval").getStatusCode().value());
        verify(agents, times(1)).knowledgeQa(actor.session(), "agent-1", true, "kb_faq", "FAQ", null);
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    private Map<String, Object> frozen() {
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("AGENT_WORKFLOW_PUBLISH"), eq("AGENT:agent-1"), frozen.capture());
        when(approvals.claim(actor, "approval", "AGENT_WORKFLOW_PUBLISH"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "AGENT_WORKFLOW_PUBLISH", "AGENT:agent-1", frozen.getValue()));
        return frozen.getValue();
    }

    private void stub(long active, long latest) {
        doReturn(ResponseEntity.ok(Map.of("agentId", "agent-1", "projectId", 3, "projectCode", "orders", "activeConfigVersionId", active,
                "staleCount", 1, "tools", List.of(Map.of("workflowId", "wf-1", "pinnedWorkflowVersionId", 11, "latestWorkflowVersionId", latest, "stale", true)))))
                .when(reads).getAgentWorkflowFreshness(actor.session(), "agent-1");
    }
}
