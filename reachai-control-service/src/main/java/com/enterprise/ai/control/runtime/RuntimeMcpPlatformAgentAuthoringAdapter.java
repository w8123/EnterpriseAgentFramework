package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAgentAuthoringPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Preserves the verified actor and reuses the human console's project checks. */
@Component
@RequiredArgsConstructor
final class RuntimeMcpPlatformAgentAuthoringAdapter implements McpPlatformAgentAuthoringPort {
    private final ControlRuntimePublicController controller;
    private final RuntimeMcpActorScope actorScope;
    private final PlatformDelegatedRequestScope delegate = new PlatformDelegatedRequestScope();

    @Override
    public ResponseEntity<?> create(PlatformAuthenticatedSession actor, Map<String, Object> body) {
        return actorScope.run(actor, () -> controller.createAgent(body));
    }

    @Override
    public ResponseEntity<?> readDraft(PlatformAuthenticatedSession actor, String agentId) {
        return actorScope.run(actor, () -> controller.platformAgentDraftContext(agentId));
    }

    @Override
    public ResponseEntity<?> saveDraft(PlatformAuthenticatedSession actor, String agentId, String baseRevision,
                                       Map<String, Object> changes) {
        return delegate.run(actor, request -> controller.platformAgentDraftSave(request, agentId, baseRevision, changes));
    }

    @Override
    public ResponseEntity<?> copyVersionToDraft(PlatformAuthenticatedSession actor, String agentId,
                                                Long configVersionId, String baseRevision) {
        return actorScope.run(actor, () -> controller.platformAgentDraftCopy(agentId, configVersionId, baseRevision));
    }

    @Override
    public ResponseEntity<?> publicationContext(PlatformAuthenticatedSession actor, String agentId, Long versionId, String revision) {
        return actorScope.run(actor, () -> controller.platformAgentPublicationContext(agentId, versionId, revision));
    }

    @Override
    public ResponseEntity<?> publishApproved(PlatformAuthenticatedSession actor, String agentId, Long versionId, String revision,
                                              Long projectId, String projectCode, Map<String, Long> workflowVersions) {
        return delegate.run(actor, request -> controller.platformAgentPublishApproved(request, agentId, versionId, revision,
                projectId, projectCode, workflowVersions));
    }
}
