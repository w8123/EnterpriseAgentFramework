package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.port.McpPlatformDebugSessionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Reuses the console's debug permission and project checks with the verified actor. */
@Component
@RequiredArgsConstructor
final class RuntimeMcpPlatformDebugSessionAdapter implements McpPlatformDebugSessionPort {
    private final ControlRuntimePublicController runtime;
    private final RuntimeMcpActorScope actorScope;

    @Override
    public ResponseEntity<?> get(PlatformAuthenticatedSession actor, String sessionId) {
        return actorScope.run(actor, () -> runtime.getRuntimeDebugSession(sessionId));
    }

    @Override
    public ResponseEntity<?> getByCreationKey(PlatformAuthenticatedSession actor, String creationKey) {
        return actorScope.run(actor, () -> runtime.getRuntimeDebugSessionByCreationKey(creationKey));
    }

    @Override
    public ResponseEntity<?> cancel(PlatformAuthenticatedSession actor, String sessionId) {
        return actorScope.run(actor, () -> runtime.cancelRuntimeDebugSession(sessionId));
    }
}
