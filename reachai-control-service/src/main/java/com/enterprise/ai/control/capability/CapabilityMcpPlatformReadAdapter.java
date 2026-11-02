package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityReadPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Reuses console checks and the Capability owner's signed scope and identity checks. */
@Component
@RequiredArgsConstructor
final class CapabilityMcpPlatformReadAdapter implements McpPlatformCapabilityReadPort {
    private final CapabilityCatalogConsoleController catalog;
    private final HttpApiConsoleController httpApis;
    private final HttpApiReferenceController httpApiReferences;
    private final CapabilityReviewConsoleController reviews;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> listBusinessMethods(PlatformAuthenticatedSession actor, long projectId,
                                                 int page, int size, String keyword, Boolean enabled) {
        return scope.run(actor, request ->
                catalog.listBusinessMethods(request, page, size, keyword, enabled, projectId));
    }

    @Override
    public ResponseEntity<?> listTools(PlatformAuthenticatedSession actor, Long projectId, int page, int size, String keyword,
                                       String source, Boolean enabled) {
        return scope.run(actor, request -> catalog.list(request, page, size, keyword, source, enabled, projectId));
    }

    @Override
    public ResponseEntity<?> getBusinessMethod(PlatformAuthenticatedSession actor, String qualifiedName) {
        return scope.run(actor, request -> catalog.getBusinessMethod(request, qualifiedName));
    }

    @Override
    public ResponseEntity<?> listHttpApis(PlatformAuthenticatedSession actor, long projectId, int page, int size,
                                         String keyword, String method, String sourceStatus) {
        return scope.run(actor, request ->
                httpApis.list(request, projectId, null, keyword, method, sourceStatus, page, size));
    }

    @Override
    public ResponseEntity<?> getHttpApi(PlatformAuthenticatedSession actor, long apiId) {
        return scope.run(actor, request -> httpApis.detail(request, apiId));
    }

    @Override
    public ResponseEntity<?> httpApiReferences(PlatformAuthenticatedSession actor, long apiId) {
        return scope.run(actor, request -> httpApiReferences.references(request, apiId));
    }

    @Override
    public ResponseEntity<?> capabilityReferences(PlatformAuthenticatedSession actor, String projectCode, String name) {
        return scope.run(actor, request -> reviews.capabilityReferences(request, projectCode, name));
    }

    @Override
    public ResponseEntity<?> capabilityChanges(PlatformAuthenticatedSession actor, String projectCode, String state, String keyword,
                                               int page, int size) {
        return scope.run(actor, request -> reviews.listChanges(request, projectCode, state, keyword, page, size));
    }
}
