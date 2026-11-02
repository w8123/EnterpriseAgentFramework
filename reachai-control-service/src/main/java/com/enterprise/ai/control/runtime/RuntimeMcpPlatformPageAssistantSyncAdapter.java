package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPageAssistantSyncPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Runs the console sync chain under the connection owner, so permission and project checks stay identical. */
@Component
@RequiredArgsConstructor
final class RuntimeMcpPlatformPageAssistantSyncAdapter implements McpPlatformPageAssistantSyncPort {
    private final ControlRuntimePublicController runtime;
    private final RuntimeMcpActorScope actorScope;

    @Override
    public ResponseEntity<?> context(PlatformAuthenticatedSession actor, String workflowId) {
        return actorScope.run(actor, () -> runtime.platformPageAssistantAttachContext(workflowId));
    }

    @Override
    public ResponseEntity<?> attach(PlatformAuthenticatedSession actor, String workflowId, Map<String, Object> request) {
        return actorScope.run(actor, () -> runtime.platformAttachPageAssistant(workflowId, request));
    }
}
