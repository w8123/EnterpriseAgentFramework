package com.enterprise.ai.control.model;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Uses the console's authorized, owner-masked model projection; never calls the secret-bearing internal API. */
@Component
@RequiredArgsConstructor
final class ModelMcpPlatformCatalogAdapter implements McpPlatformModelCatalogPort {
    private final ControlModelInstanceController models;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> list(PlatformAuthenticatedSession actor, String projectCode, String modelType,
                                   String provider, boolean includeArchived, int offset, int limit) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (projectCode != null) parameters.put("projectCode", projectCode);
        if (modelType != null) parameters.put("modelType", modelType);
        if (provider != null) parameters.put("provider", provider);
        parameters.put("includeArchived", String.valueOf(includeArchived));
        return scope.run(actor, request -> {
            var response = models.list(request, parameters);
            if (!response.getStatusCode().is2xxSuccessful()) return ResponseEntity.status(response.getStatusCode()).build();
            if (response.getBody() == null || !(response.getBody().get("data") instanceof List<?> visible)) {
                return ResponseEntity.status(502).build();
            }
            // Filter by real project grants in the controller before pagination, including total counts.
            return ResponseEntity.ok(Map.of("items", visible.stream().skip(offset).limit(limit).toList(),
                    "offset", offset, "limit", limit, "total", visible.size()));
        });
    }

    @Override
    public ResponseEntity<?> get(PlatformAuthenticatedSession actor, String modelId) {
        return scope.run(actor, request -> models.get(request, modelId));
    }

    @Override
    public ResponseEntity<?> create(PlatformAuthenticatedSession actor, Map<String, Object> body) {
        return scope.run(actor, request -> models.create(request, body));
    }
}
