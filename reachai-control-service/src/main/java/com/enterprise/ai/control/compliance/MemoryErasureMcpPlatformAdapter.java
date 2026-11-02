package com.enterprise.ai.control.compliance;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformMemoryErasurePort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Calls the compliance console as the connection's own account, so its global permission check and audit apply. */
@Component
@RequiredArgsConstructor
class MemoryErasureMcpPlatformAdapter implements McpPlatformMemoryErasurePort {
    private final MemoryErasureController controller;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> get(PlatformAuthenticatedSession actor, String requestId) {
        return scope.run(actor, request -> controller.get(request, requestId));
    }

    @Override
    public ResponseEntity<?> create(PlatformAuthenticatedSession actor, Map<String, Object> command) {
        var value = new MemoryErasureOrchestrationService.CreateCommand(text(command, "confirmation"), text(command, "clientRequestId"),
                text(command, "tenantId"), text(command, "runtimeUserId"), text(command, "reasonCode"), text(command, "referenceId"));
        return scope.run(actor, request -> controller.create(request, value));
    }

    @Override
    public ResponseEntity<?> retry(PlatformAuthenticatedSession actor, String requestId) {
        return scope.run(actor, request -> controller.retry(request, requestId));
    }

    private static String text(Map<String, Object> command, String key) {
        Object value = command == null ? null : command.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
