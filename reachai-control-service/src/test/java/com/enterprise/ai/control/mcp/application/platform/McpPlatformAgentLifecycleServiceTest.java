package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAgentLifecyclePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformReadPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformAgentLifecycleServiceTest {
    private final McpPlatformReadPort reads = mock(McpPlatformReadPort.class);
    private final McpPlatformAgentLifecyclePort agents = mock(McpPlatformAgentLifecyclePort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformAgentLifecycleService service = new McpPlatformAgentLifecycleService(reads, agents, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of("agent:write"), List.of()));

    @Test void disableFreezesWhoTheAgentServesAndIgnoresDraftAndFreshnessChurn() {
        stub(true, 7, 0);
        service.request(actor, McpPlatformAgentLifecycleService.Change.DISABLE, "erp", "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("AGENT_LIFECYCLE_CHANGE"), eq("AGENT:erp"), frozen.capture());
        when(approvals.claim(actor, "approval", "AGENT_LIFECYCLE_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "AGENT_LIFECYCLE_CHANGE", "AGENT:erp", frozen.getValue()));

        stub(true, 8, 0);
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(agents, never()).setEnabled(any(), any(), anyBoolean());

        stub(true, 7, 3);
        doReturn(ResponseEntity.ok(Map.of("id", "erp", "enabled", false))).when(agents).setEnabled(actor.session(), "erp", false);
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    @Test void enablingAnEnabledAgentIsRefused() {
        stub(true, 7, 0);
        assertEquals(409, service.request(actor, McpPlatformAgentLifecycleService.Change.ENABLE, "erp", "inv").getStatusCode().value());
        verifyNoInteractions(approvals);
    }

    private void stub(boolean enabled, long activeVersion, int stale) {
        var agent = new LinkedHashMap<String, Object>();
        agent.put("id", "erp"); agent.put("name", "ERP 助手"); agent.put("projectCode", "orders"); agent.put("enabled", enabled);
        agent.put("activeConfigVersionId", activeVersion); agent.put("staleWorkflowToolCount", stale);
        doReturn(ResponseEntity.ok(agent)).when(reads).getAgent(actor.session(), "erp");
    }
}
