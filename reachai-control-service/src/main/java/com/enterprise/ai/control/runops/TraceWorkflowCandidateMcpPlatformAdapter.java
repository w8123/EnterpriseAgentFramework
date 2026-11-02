package com.enterprise.ai.control.runops;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformRunCandidatePort;
import com.enterprise.ai.control.runops.TraceWorkflowCandidateApplicationService.CreateCandidateTaskRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Calls the run-center candidate endpoints as the connection's account, so their project checks apply. */
@Component
@RequiredArgsConstructor
class TraceWorkflowCandidateMcpPlatformAdapter implements McpPlatformRunCandidatePort {
    private final TraceWorkflowCandidateController controller;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> eligibility(PlatformAuthenticatedSession actor, String traceId) {
        return scope.run(actor, request -> controller.eligibility(request, traceId));
    }

    @Override
    public ResponseEntity<?> create(PlatformAuthenticatedSession actor, String traceId, String executorProvider) {
        return scope.run(actor, request -> controller.createTask(request, traceId, new CreateCandidateTaskRequest(executorProvider, null)));
    }
}
