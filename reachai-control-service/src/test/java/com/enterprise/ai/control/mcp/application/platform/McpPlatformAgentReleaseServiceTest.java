package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAgentAuthoringPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpPlatformAgentReleaseServiceTest {
    private final McpPlatformAgentAuthoringPort authoring = mock(McpPlatformAgentAuthoringPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformAgentReleaseService service = new McpPlatformAgentReleaseService(authoring, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "pai_19", null,
                    List.of(), List.of(), List.of()));
    private static final String REVISION = "a".repeat(64);
    private static final String REQUEST = "00000000-0000-0000-0000-000000000001";

    @Test void freezesOnlyTheServerPublicationSnapshotAndDoesNotDispatch() {
        var context = context();
        doReturn(ResponseEntity.ok(context)).when(authoring).publicationContext(actor.session(), "agent-1", 8L, REVISION);
        assertEquals(200, service.request(actor, "agent-1", 8L, REVISION, REQUEST).getStatusCode().value());
        @SuppressWarnings("unchecked") var arguments = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq(PlatformAiApprovalService.AGENT_PUBLISH), eq("AGENT:agent-1"), arguments.capture());
        assertEquals(Map.of("wf-1", 100L), arguments.getValue().get("workflowVersions"));
        assertEquals(REQUEST, arguments.getValue().get("invocationId"));
        assertEquals("审核配置", ((Map<?, ?>) arguments.getValue().get("snapshot")).get("systemPrompt"));
        verify(authoring, never()).publishApproved(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void anApprovalCannotPublishWhenTheProjectOrWorkflowVersionsDrift() {
        for (String field : List.of("projectCode", "activeConfigVersionId", "workflowVersions")) {
            reset(approvals, authoring);
            var frozen = frozen();
            when(approvals.claim(actor, "approval-1", PlatformAiApprovalService.AGENT_PUBLISH))
                    .thenReturn(new PlatformAiApprovalService.Claim("approval-1", PlatformAiApprovalService.AGENT_PUBLISH, "AGENT:agent-1", frozen));
            var changed = context();
            changed.put(field, field.equals("workflowVersions") ? Map.of("wf-1", 101L) : field.equals("projectCode") ? "billing" : 99L);
            doReturn(ResponseEntity.ok(changed)).when(authoring).publicationContext(actor.session(), "agent-1", 8L, REVISION);
            assertEquals(409, service.execute(actor, "approval-1").getStatusCode().value());
            verify(approvals).finish(actor, "approval-1", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            verify(authoring, never()).publishApproved(any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test void consumesOneApprovalAndDispatchesOnlyItsFrozenPins() {
        arrangeClaim();
        var result = context();
        result.put("base", Map.of("agentId", "agent-1", "id", 8L, "status", "ACTIVE"));
        doReturn(ResponseEntity.ok(result)).when(authoring).publishApproved(actor.session(), "agent-1", 8L, REVISION, 7L, "orders", Map.of("wf-1", 100L));
        assertEquals(200, service.execute(actor, "approval-1").getStatusCode().value());
        verify(approvals).finish(actor, "approval-1", "SUCCEEDED", null);
        verify(authoring).publishApproved(actor.session(), "agent-1", 8L, REVISION, 7L, "orders", Map.of("wf-1", 100L));
    }

    @Test void aLostResponseOrMalformedSuccessIsUnknownAndNeverReplayed() {
        arrangeClaim();
        doThrow(new IllegalStateException("private transport detail")).when(authoring)
                .publishApproved(any(), any(), any(), any(), any(), any(), any());
        var result = service.execute(actor, "approval-1");
        assertEquals(202, result.getStatusCode().value());
        assertEquals("UNKNOWN", ((Map<?, ?>) result.getBody()).get("status"));
        assertFalse(String.valueOf(result.getBody()).contains("private"));
        verify(approvals).finish(actor, "approval-1", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        verify(authoring, times(1)).publishApproved(any(), any(), any(), any(), any(), any(), any());
        reset(approvals, authoring);
        arrangeClaim();
        doReturn(ResponseEntity.ok(Map.of("agentId", "agent-1", "base", Map.of("id", "bad"))))
                .when(authoring).publishApproved(any(), any(), any(), any(), any(), any(), any());
        assertEquals(202, service.execute(actor, "approval-1").getStatusCode().value());
        verify(approvals).finish(actor, "approval-1", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    private void arrangeClaim() {
        when(approvals.claim(actor, "approval-1", PlatformAiApprovalService.AGENT_PUBLISH))
                .thenReturn(new PlatformAiApprovalService.Claim("approval-1", PlatformAiApprovalService.AGENT_PUBLISH, "AGENT:agent-1", frozen()));
        doReturn(ResponseEntity.ok(context())).when(authoring).publicationContext(actor.session(), "agent-1", 8L, REVISION);
    }

    private static Map<String, Object> frozen() {
        var result = context();
        result.put("baseRevision", result.remove("revision")); result.put("snapshot", result.remove("base"));
        result.put("configVersionId", 8L); result.put("invocationId", REQUEST);
        return result;
    }

    private static Map<String, Object> context() {
        return new LinkedHashMap<>(Map.of("agentId", "agent-1", "projectId", 7L, "projectCode", "orders", "revision", REVISION,
                "activeConfigVersionId", 4L, "base", Map.of("agentId", "agent-1", "id", 8L, "status", "DRAFT", "systemPrompt", "审核配置"),
                "workflowVersions", Map.of("wf-1", 100L), "identity", Map.of("name", "订单助手", "visibility", "PROJECT", "enabled", true)));
    }
}
