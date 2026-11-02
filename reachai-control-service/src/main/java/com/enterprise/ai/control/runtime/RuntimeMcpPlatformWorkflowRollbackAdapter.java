package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowRollbackPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
final class RuntimeMcpPlatformWorkflowRollbackAdapter implements McpPlatformWorkflowRollbackPort {
    private final ControlRuntimePublicController runtime;
    private final RuntimeMcpActorScope actorScope;

    @Override
    public ResponseEntity<?> rollback(PlatformAuthenticatedSession actor, String workflowId, long versionId, String baseRevision) {
        return actorScope.run(actor, () -> runtime.rollbackWorkflowVersion(workflowId, versionId, Map.of("baseRevision", baseRevision)));
    }
}
