package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAgentLifecyclePort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
final class RuntimeMcpPlatformAgentLifecycleAdapter implements McpPlatformAgentLifecyclePort {
    private final ControlRuntimePublicController runtime;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> setEnabled(PlatformAuthenticatedSession actor, String agentId, boolean enabled) {
        // The Agent list's switch sends exactly this body.
        return scope.run(actor, request -> runtime.updateAgent(agentId, Map.of("enabled", enabled)));
    }

    @Override
    public ResponseEntity<?> delete(PlatformAuthenticatedSession actor, String agentId) {
        return scope.run(actor, request -> runtime.deleteAgent(agentId));
    }
}
