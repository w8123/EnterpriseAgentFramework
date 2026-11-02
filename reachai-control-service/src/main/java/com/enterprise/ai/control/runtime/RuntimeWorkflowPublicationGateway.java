package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Workflow-owner facts are read after checking both permissions in the same project. */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowPublicationGateway implements com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowReleasePort {
    private final RuntimeProxyClient runtime;
    private final RuntimeManagementAccess access;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper json;

    public ResponseEntity<Object> context(PlatformAuthenticatedSession actor, String workflowId, String revision, String version, String note) {
        Map<String, Object> command = command(actor, workflowId, revision, version, note);
        return send(actor, command, false);
    }

    public ResponseEntity<Object> publish(PlatformAuthenticatedSession actor, String workflowId, String revision, String version, String note,
                                          Long expectedProjectId, String expectedProjectCode, String digest) {
        Map<String, Object> command = command(actor, workflowId, revision, version, note);
        if (!java.util.Objects.equals(expectedProjectId, command.get("projectId"))
                || !java.util.Objects.equals(expectedProjectCode, command.get("projectCode"))
                || digest == null || !digest.matches("[0-9a-f]{64}")) throw new ResponseStatusException(HttpStatus.CONFLICT);
        command.put("expectedPublicationDigest", digest);
        return send(actor, command, true);
    }

    private Map<String, Object> command(PlatformAuthenticatedSession actor, String id, String revision, String version, String note) {
        if (actor == null || actor.user() == null || actor.user().getId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var response = runtime.getWorkflow(id);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        access.requireResponseProject(actor, PlatformPermissions.WORKFLOW_READ, response);
        access.requireResponseProject(actor, PlatformPermissions.WORKFLOW_PUBLISH, response);
        if (!(response.getBody() instanceof Map<?, ?> owner) || !id.equals(owner.get("id"))
                || !owner.containsKey("projectId") || !owner.containsKey("projectCode")
                || owner.get("projectId") != null && !(owner.get("projectId") instanceof Number)
                || owner.get("projectCode") != null && !(owner.get("projectCode") instanceof String)) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("contractVersion", 1); command.put("workflowId", id);
        try { command.put("projectId", owner.get("projectId") == null ? null : Long.valueOf(owner.get("projectId").toString())); }
        catch (NumberFormatException invalid) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY); }
        command.put("projectCode", owner.get("projectCode")); command.put("baseRevision", revision);
        command.put("version", version); command.put("note", note);
        return command;
    }

    private ResponseEntity<Object> send(PlatformAuthenticatedSession actor, Map<String, Object> command, boolean publish) {
        String path = "/internal/runtime/workflow-releases/" + (publish ? "publish" : "context");
        byte[] bytes;
        try { bytes = json.writeValueAsBytes(command); }
        catch (Exception invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        String project = (String) command.get("projectCode");
        var headers = signer.sign("POST", path, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                project == null || project.isBlank() ? "default" : project, String.valueOf(actor.user().getId()), bytes);
        return publish ? runtime.publishApprovedWorkflow(headers, bytes) : runtime.workflowPublicationContext(headers, bytes);
    }
}
