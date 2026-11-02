package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAgentWorkflowPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Calls the console endpoints as the connection's account; the publisher is always that account. */
@Component
@RequiredArgsConstructor
final class RuntimeMcpPlatformAgentWorkflowAdapter implements McpPlatformAgentWorkflowPort {
    private final ControlRuntimePublicController runtime;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> publishLatest(PlatformAuthenticatedSession actor, String agentId, long expectedActiveConfigVersionId) {
        Map<String, Object> body = Map.of("expectedActiveConfigVersionId", expectedActiveConfigVersionId, "publishedBy", actor.user().getUsername());
        return scope.run(actor, request -> runtime.publishLatestAgentWorkflowVersions(request, agentId, body));
    }

    @Override
    public ResponseEntity<?> knowledgeQa(PlatformAuthenticatedSession actor, String agentId, boolean attachToAgent, String knowledgeBaseCode,
                                         String knowledgeBaseName, String modelInstanceId) {
        Map<String, Object> body = new HashMap<>();
        body.put("attachToAgent", attachToAgent);
        body.put("knowledgeBaseCode", knowledgeBaseCode);
        body.put("knowledgeBaseName", knowledgeBaseName);
        body.put("modelInstanceId", modelInstanceId);
        body.put("publishedBy", actor.user().getUsername());
        return scope.run(actor, request -> runtime.attachKnowledgeQaWorkflow(agentId, body));
    }
}
