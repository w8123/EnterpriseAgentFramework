package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import org.springframework.http.ResponseEntity;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

final class AgentDraftPublishTestAccess {
    static final String REVISION = "a".repeat(64);
    static RuntimeAgentDraftGateway configure(ControlRuntimePublicController controller, RuntimeProxyClient runtime, String code) {
        var access = mock(RuntimeManagementAccess.class);
        var session = new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "reviewer", "Reviewer"), "session", null,
                List.of(), List.of(), List.of());
        when(access.require(PlatformPermissions.AGENT_PUBLISH)).thenReturn(session);
        controller.setRuntimeManagementAccess(access);
        var gateway = mock(RuntimeAgentDraftGateway.class);
        controller.setAgentDraftGateway(gateway);
        when(runtime.getAgent("agent-1")).thenReturn(ResponseEntity.ok(Map.of("id", "agent-1", "projectId", 7L, "projectCode", code)));
        when(gateway.publish(eq("agent-1"), eq(7L), eq(code), eq(REVISION), eq(31L), eq(session)))
                .thenReturn(ResponseEntity.ok(Map.of("agentId", "agent-1", "projectId", 7L, "projectCode", code,
                        "revision", "b".repeat(64), "base", Map.of("id", 31L, "status", "ACTIVE"))));
        return gateway;
    }
}
