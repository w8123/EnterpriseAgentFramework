package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Signs the exact release command bytes and binds the authenticated platform principal. */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowReleaseGateway {
    private final RuntimeProxyClient runtime;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper json;

    public ResponseEntity<Object> publish(String workflowId, Map<String, Object> body, PlatformAuthenticatedSession session) {
        byte[] payload = payload(body, List.of("version", "rolloutPercent", "note", "baseRevision"));
        String path = releasePath(workflowId) + "/publish";
        return runtime.publishWorkflowVersion(workflowId, headers(path, payload, session), payload);
    }

    public ResponseEntity<Object> rollback(String workflowId, Long versionId, Map<String, Object> body,
                                           PlatformAuthenticatedSession session) {
        if (versionId == null || versionId <= 0) throw new IllegalArgumentException("versionId is required");
        byte[] payload = payload(body, List.of("baseRevision"));
        String path = releasePath(workflowId) + "/" + versionId + "/rollback";
        return runtime.rollbackWorkflowVersion(workflowId, versionId, headers(path, payload, session), payload);
    }

    private String releasePath(String workflowId) {
        if (workflowId == null || !workflowId.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("invalid workflowId");
        }
        return "/api/workflows/" + workflowId + "/versions";
    }

    private Map<String, String> headers(String path, byte[] payload, PlatformAuthenticatedSession session) {
        if (session == null || session.user() == null || session.user().getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Platform session is required for Workflow release");
        }
        return signer.sign("POST", path, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                "default", String.valueOf(session.user().getId()), payload);
    }

    private byte[] payload(Map<String, Object> body, List<String> fields) {
        Map<String, Object> command = new LinkedHashMap<>();
        if (body != null) fields.forEach(field -> {
            if (body.containsKey(field)) command.put(field, body.get(field));
        });
        try { return json.writeValueAsBytes(command); }
        catch (Exception invalid) { throw new IllegalArgumentException("Workflow release command cannot be serialized", invalid); }
    }
}
