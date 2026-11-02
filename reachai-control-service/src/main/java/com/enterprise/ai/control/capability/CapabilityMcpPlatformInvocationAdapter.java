package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityInvocationPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reuses the console's ACL, owner identity, version pin and idempotent invocation ledger. */
@Component
@RequiredArgsConstructor
final class CapabilityMcpPlatformInvocationAdapter implements McpPlatformCapabilityInvocationPort {
    private final CapabilityInvocationConsoleController controller;
    private final HttpApiConsoleController httpApis;
    private final PlatformDelegatedRequestScope scope;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<?> context(PlatformAuthenticatedSession actor, String qualifiedName) {
        return scope.run(actor, request -> controller.context(request, qualifiedName));
    }

    @Override
    public ResponseEntity<?> invokeReadOnly(PlatformAuthenticatedSession actor, String qualifiedName,
                                            Map<String, Object> body) {
        Map<String, Object> safe = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        // AI input can never supply the confirmation required for a write-side effect.
        safe.put("confirmedSideEffect", false);
        return scope.run(actor, request -> controller.invoke(request, qualifiedName, safe));
    }

    @Override
    public ResponseEntity<?> invokeApproved(PlatformAuthenticatedSession actor, String qualifiedName,
                                            Map<String, Object> body) {
        Map<String, Object> safe = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        // Only the approval executor calls this port after an atomic claim.
        safe.put("confirmedSideEffect", true);
        return scope.run(actor, request -> controller.invoke(request, qualifiedName, safe));
    }

    @Override
    public ResponseEntity<?> get(PlatformAuthenticatedSession actor, String invocationId) {
        return scope.run(actor, request -> controller.get(request, invocationId));
    }

    @Override
    public ResponseEntity<?> httpConnection(PlatformAuthenticatedSession actor, long apiId) {
        return scope.run(actor, request -> {
            ResponseEntity<Object> response = httpApis.connection(request, apiId);
            if (response == null || !response.getStatusCode().is2xxSuccessful()) return response;
            Map<String, Object> current = connectionMap(response.getBody());
            if (current == null) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            Map<String, Object> projection = new LinkedHashMap<>();
            for (String field : List.of("qualifiedName", "projectId", "projectCode", "environment", "origin",
                    "authMode", "credentialName", "credentialRevision", "revision", "status", "blockingReason",
                    "previewUrl", "connectionSource", "inherited", "overrideActive", "overrideRevision")) {
                Object value = current.get(field);
                if (value instanceof String || value instanceof Number || value instanceof Boolean) projection.put(field, value);
            }
            return ResponseEntity.ok(projection);
        });
    }

    @Override
    public ResponseEntity<?> httpWriteContext(PlatformAuthenticatedSession actor, long apiId) {
        return scope.run(actor, request -> {
            ResponseEntity<Object> targetResponse = httpApis.platformAiWriteContext(request, apiId);
            if (targetResponse == null || !targetResponse.getStatusCode().is2xxSuccessful()) return targetResponse;
            Map<String, Object> target = connectionMap(targetResponse.getBody());
            ResponseEntity<Object> connectionResponse = httpApis.connection(request, apiId);
            if (connectionResponse == null || !connectionResponse.getStatusCode().is2xxSuccessful()) return connectionResponse;
            Map<String, Object> current = connectionMap(connectionResponse.getBody());
            if (target == null || current == null || !(target.get("projectId") instanceof Number ownerProject)
                    || !(current.get("projectId") instanceof Number connectionProject)
                    || ownerProject.longValue() != connectionProject.longValue()
                    || !Objects.equals(target.get("qualifiedName"), current.get("qualifiedName"))
                    || !Objects.equals(target.get("projectCode"), current.get("projectCode"))
                    || !Objects.equals(target.get("environment"), current.get("environment"))
                    || !"CONFIGURED".equals(current.get("status"))
                    || !(current.get("revision") instanceof Number revision) || revision.longValue() <= 0) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "HTTP_API_CONNECTION_CHANGED"));
            }
            boolean hasCredential = current.get("credentialRef") instanceof String ref && !ref.isBlank();
            Object credentialRevision = current.get("credentialRevision");
            if (hasCredential && (!(credentialRevision instanceof String value) || !value.matches("[a-f0-9]{64}"))) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "HTTP_API_CREDENTIAL_REVISION_UNAVAILABLE"));
            }
            Map<String, Object> projection = new LinkedHashMap<>(target);
            projection.put("connectionRevision", revision.longValue());
            projection.put("credentialRequired", hasCredential);
            if (credentialRevision instanceof String value && !value.isBlank()) projection.put("credentialRevision", value);
            if (current.get("authMode") instanceof String mode) projection.put("authMode", mode);
            String targetUrl = current.get("previewUrl") instanceof String url && !url.isBlank()
                    ? url : String.valueOf(target.get("routeTemplate"));
            projection.put("targetDescription", target.get("httpMethod") + " · " + targetUrl);
            return ResponseEntity.ok(projection);
        });
    }

    @Override
    public ResponseEntity<?> invokeHttpReadOnly(PlatformAuthenticatedSession actor, long apiId,
                                                Map<String, Object> body) {
        return invokeHttp(actor, apiId, body, false);
    }

    @Override
    public ResponseEntity<?> invokeHttpApproved(PlatformAuthenticatedSession actor, long apiId,
                                                Map<String, Object> body) {
        return invokeHttp(actor, apiId, body, true);
    }

    private ResponseEntity<?> invokeHttp(PlatformAuthenticatedSession actor, long apiId,
                                         Map<String, Object> body, boolean approved) {
        Map<String, Object> safe = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        safe.put("confirmedSideEffect", approved);
        return scope.run(actor, request -> {
            ResponseEntity<Object> connection = httpApis.connection(request, apiId);
            if (connection == null || !connection.getStatusCode().is2xxSuccessful()) return connection;
            Map<String, Object> current = connectionMap(connection.getBody());
            if (current == null) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            Object revision = current.get("credentialRevision");
            if (current.get("credentialRef") instanceof String ref && !ref.isBlank()
                    && (!(revision instanceof String value) || value.isBlank())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("code", "HTTP_API_CREDENTIAL_REVISION_UNAVAILABLE"));
            }
            if (revision instanceof String value && !value.isBlank()
                    && !value.equals(safe.get("expectedCredentialRevision"))) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "HTTP_API_CREDENTIAL_CHANGED"));
            }
            return httpApis.invoke(request, apiId, safe);
        });
    }

    @Override
    public ResponseEntity<?> getHttpInvocation(PlatformAuthenticatedSession actor, String invocationId, String projectCode) {
        return scope.run(actor, request -> httpApis.getInvocation(request, invocationId, projectCode));
    }

    private Map<String, Object> connectionMap(Object body) {
        try {
            return json.convertValue(body, new TypeReference<>() { });
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
