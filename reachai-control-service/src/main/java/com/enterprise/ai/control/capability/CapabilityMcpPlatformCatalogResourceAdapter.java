package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCatalogResourcePort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.stereotype.Component;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.Map;

/** Runs catalog resource calls through the facade the business capability and API graph pages use. */
@Component
@RequiredArgsConstructor
final class CapabilityMcpPlatformCatalogResourceAdapter implements McpPlatformCatalogResourcePort {
    private final CatalogResourcesConsoleController catalog;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> call(PlatformAuthenticatedSession actor, Area area, String method, long projectId, String path,
                                  Map<String, String> query, Object body) {
        String uri = (area == Area.API_GRAPH ? "/api/api-graph/projects/" : "/api/business-capabilities/projects/") + projectId + path;
        return scope.run(actor, request -> catalog.resource(at(request, method, uri),
                new RequestEntity<>(body, HttpMethod.valueOf(method), URI.create(uri)), projectId, path, query));
    }

    // The facade picks the owner area from the URI and the permission from the method.
    private static HttpServletRequest at(HttpServletRequest request, String method, String uri) {
        return new HttpServletRequestWrapper(request) {
            @Override public String getRequestURI() { return uri; }
            @Override public String getMethod() { return method; }
        };
    }
}
