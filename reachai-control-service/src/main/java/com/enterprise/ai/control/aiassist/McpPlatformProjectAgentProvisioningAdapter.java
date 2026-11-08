package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectAgentProvisioningPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Reuses the console entry and its project authorization for platform AI. */
@Component
@RequiredArgsConstructor
class McpPlatformProjectAgentProvisioningAdapter implements McpPlatformProjectAgentProvisioningPort {
    private final ControlAiAssistProjectProvisioningController controller;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> provision(PlatformAuthenticatedSession actor, long projectId) {
        return scope.run(actor, request -> controller.provision(projectId, Map.of(), request));
    }
}
