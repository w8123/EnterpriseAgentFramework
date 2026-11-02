package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowCredentialPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Reuses the console's credential permission and project checks with the verified actor. */
@Component
@RequiredArgsConstructor
final class RuntimeMcpPlatformWorkflowCredentialAdapter implements McpPlatformWorkflowCredentialPort {
    private final ControlRuntimePublicController runtime;
    private final RuntimeMcpActorScope actorScope;

    @Override
    public ResponseEntity<?> list(PlatformAuthenticatedSession actor, Long projectId, String projectCode) {
        return actorScope.run(actor, () -> runtime.listWorkflowCredentials(projectId, projectCode));
    }

    @Override
    public ResponseEntity<?> create(PlatformAuthenticatedSession actor, Map<String, Object> body) {
        return actorScope.run(actor, () -> runtime.createWorkflowCredential(body));
    }

    @Override
    public ResponseEntity<?> update(PlatformAuthenticatedSession actor, long id, Map<String, Object> body) {
        return actorScope.run(actor, () -> runtime.updateWorkflowCredential(id, body));
    }

    @Override
    public ResponseEntity<?> disable(PlatformAuthenticatedSession actor, long id) {
        return actorScope.run(actor, () -> runtime.deleteWorkflowCredential(id));
    }
}
