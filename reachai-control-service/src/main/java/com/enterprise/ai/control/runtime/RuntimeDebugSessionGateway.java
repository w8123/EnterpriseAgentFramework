package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Authorizes the canonical Workflow project and signs the exact command with the platform session owner. */
@Service
@RequiredArgsConstructor
public class RuntimeDebugSessionGateway {
    private static final String BASE = "/api/runtime/debug-sessions";
    private static final String PERMISSION = PlatformPermissions.WORKFLOW_DEBUG;
    private static final List<String> CREATE_FIELDS = List.of("targetType", "workingCopyDefinition", "message", "inputParams", "debugOptions", "idempotencyKey");
    private static final List<String> SUBMIT_FIELDS = List.of("action", "values", "message", "interactionId", "idempotencyKey");
    private static final byte[] EMPTY = new byte[0];
    private final RuntimeProxyClient runtime;
    private final RuntimeAgentStreamProxy streams;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper json;
    private final RuntimeManagementAccess access;

    public ResponseEntity<Object> create(Map<String, Object> body) {
        var session = authorizeCreation(body);
        byte[] payload = payload(body, CREATE_FIELDS);
        return runtime.createRuntimeDebugSession(headers("POST", BASE, payload, session), payload);
    }

    public ResponseEntity<Object> get(String id) {
        return existing(id, access.require(PERMISSION));
    }

    public ResponseEntity<Object> getByCreationKey(String key) {
        var session=access.require(PERMISSION);
        if(key==null || !key.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("invalid debug creation key");
        String path=BASE+"/by-creation-key/"+key;
        var response=runtime.getRuntimeDebugSessionByCreationKey(key,headers("GET",path,EMPTY,session));
        requireSuccess(response);
        access.requireResponseProject(session,PERMISSION,response);
        return response;
    }

    public ResponseEntity<Object> submit(String id, Map<String, Object> body) {
        var session = access.require(PERMISSION);
        existing(id, session);
        byte[] payload = payload(body, SUBMIT_FIELDS);
        return runtime.submitRuntimeDebugSession(id, headers("POST", path(id) + "/submit", payload, session), payload);
    }

    public ResponseEntity<Object> cancel(String id) {
        var session = access.require(PERMISSION);
        existing(id, session);
        return runtime.cancelRuntimeDebugSession(id, headers("POST", path(id) + "/cancel", EMPTY, session));
    }

    public ResponseEntity<StreamingResponseBody> streamCreate(Map<String, Object> body) {
        var session = authorizeCreation(body);
        return stream(BASE + "/stream", payload(body, CREATE_FIELDS), session);
    }

    public ResponseEntity<StreamingResponseBody> streamSubmit(String id, Map<String, Object> body) {
        var session = access.require(PERMISSION);
        existing(id, session);
        return stream(path(id) + "/submit/stream", payload(body, SUBMIT_FIELDS), session);
    }

    private PlatformAuthenticatedSession authorizeCreation(Map<String, Object> body) {
        var session = access.require(PERMISSION);
        Object definition = body == null ? null : body.get("workingCopyDefinition");
        if (!(definition instanceof Map<?, ?> candidate)) throw new IllegalArgumentException("workingCopyDefinition is required");
        Object workflowId = candidate.get("workflowId");
        if (workflowId != null && !workflowId.toString().isBlank()) {
            var saved = runtime.getWorkflow(workflowId.toString().trim());
            requireSuccess(saved);
            access.requireResponseProject(session, PERMISSION, saved);
        } else {
            Map<String, Object> project = new LinkedHashMap<>();
            project.put("projectCode", candidate.get("projectCode"));
            access.requireBodyProject(session, PERMISSION, project);
        }
        return session;
    }

    private ResponseEntity<Object> existing(String id, PlatformAuthenticatedSession session) {
        var response = runtime.getRuntimeDebugSession(id, headers("GET", path(id), EMPTY, session));
        requireSuccess(response);
        access.requireResponseProject(session, PERMISSION, response);
        return response;
    }

    private void requireSuccess(ResponseEntity<?> response) {
        if (response == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Runtime returned no resource");
        if (!response.getStatusCode().is2xxSuccessful())
            throw new ResponseStatusException(response.getStatusCode(), "Runtime resource is unavailable");
    }

    private Map<String, String> headers(String method, String path, byte[] payload, PlatformAuthenticatedSession session) {
        if (session == null || session.user() == null || session.user().getId() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Platform session is required for Workflow debug");
        return signer.sign(method, path, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                "default", String.valueOf(session.user().getId()), payload);
    }

    private ResponseEntity<StreamingResponseBody> stream(String path, byte[] payload, PlatformAuthenticatedSession session) {
        // Capture immutable bytes and the authenticated principal before leaving the request thread.
        if (session == null || session.user() == null || session.user().getId() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Platform session is required for Workflow debug");
        StreamingResponseBody body = output -> streams.streamSignedDebugSession(
                path, payload, headers("POST", path, payload, session), output);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(body);
    }

    private String path(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid debug sessionId");
        return BASE + "/" + id;
    }

    private byte[] payload(Map<String, Object> body, List<String> fields) {
        Map<String, Object> selected = new LinkedHashMap<>();
        if (body != null) fields.forEach(field -> { if (body.containsKey(field)) selected.put(field, body.get(field)); });
        try { return json.writeValueAsBytes(selected); }
        catch (Exception invalid) { throw new IllegalArgumentException("Debug command cannot be serialized", invalid); }
    }
}
