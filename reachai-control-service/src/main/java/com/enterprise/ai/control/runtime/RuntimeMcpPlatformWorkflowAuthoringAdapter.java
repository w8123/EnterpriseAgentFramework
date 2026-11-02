package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowAuthoringPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Uses the Runtime mutation service after checking the platform user's live Workflow scope. */
@Component
@RequiredArgsConstructor
final class RuntimeMcpPlatformWorkflowAuthoringAdapter implements McpPlatformWorkflowAuthoringPort {
    private final RuntimeProxyClient runtime;
    private final RuntimeManagementAccess access;
    private final RuntimeMcpActorScope actorScope;

    @Override
    public ResponseEntity<?> createDraft(PlatformAuthenticatedSession actor, Map<String, Object> body) {
        return actorScope.run(actor, () -> {
            PlatformAuthenticatedSession session = access.require(PlatformPermissions.WORKFLOW_WRITE);
            access.requireBodyProject(session, PlatformPermissions.WORKFLOW_WRITE, body);
            return runtime.createWorkflow(body);
        });
    }

    @Override
    public ResponseEntity<?> patch(PlatformAuthenticatedSession actor, String workflowId,
                                   boolean dryRun, Map<String, Object> body) {
        return actorScope.run(actor, () -> {
            if (body == null || !(body.get("baseRevision") instanceof String revision) || revision.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseRevision is required");
            }
            String permission = dryRun ? PlatformPermissions.WORKFLOW_READ : PlatformPermissions.WORKFLOW_WRITE;
            access.require(permission);
            ResponseEntity<Object> current = runtime.getWorkflow(workflowId);
            if (current == null || !current.getStatusCode().is2xxSuccessful()) return current;
            access.requireResponseProject(actor, permission, current);
            Map<String, Object> safeBody = new LinkedHashMap<>(body);
            safeBody.put("dryRun", dryRun);
            return runtime.patchWorkflowAiCoding(workflowId, safeBody);
        });
    }
}
