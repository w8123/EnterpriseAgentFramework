package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformHttpApiAcceptPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformHttpConnectionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
final class CapabilityMcpPlatformHttpConnectionAdapter implements McpPlatformHttpConnectionPort, McpPlatformHttpApiAcceptPort {
    private final HttpApiConsoleController httpApis;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> connection(PlatformAuthenticatedSession actor, long apiId) {
        return scope.run(actor, request -> httpApis.connection(request, apiId));
    }

    @Override
    public ResponseEntity<?> change(PlatformAuthenticatedSession actor, Change change, long apiId, Map<String, Object> body) {
        return scope.run(actor, request -> switch (change) {
            case SAVE_OVERRIDE -> httpApis.saveConnection(request, apiId, body);
            case SAVE_PROJECT_DEFAULT -> httpApis.saveProjectDefaultConnection(request, apiId, body);
            case REMOVE_OVERRIDE -> httpApis.removeConnectionOverride(request, apiId,
                    body.get("expectedRevision") instanceof Number revision ? revision.longValue() : null);
        });
    }

    @Override
    public ResponseEntity<?> accept(PlatformAuthenticatedSession actor, long apiId, String expectedSourceSetRevision) {
        return scope.run(actor, request -> httpApis.accept(request, apiId, Map.of("expectedSourceSetRevision", expectedSourceSetRevision)));
    }
}
