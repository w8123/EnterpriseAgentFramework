package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Binds the verified platform actor and project to the exact Runtime draft command bytes. */
@Service
@RequiredArgsConstructor
public class RuntimeAgentDraftGateway {
    private static final String CONTEXT = "/internal/runtime/agent-config-drafts/context";
    private static final String VERSIONS = "/internal/runtime/agent-config-drafts/versions";
    private static final String SAVE = "/internal/runtime/agent-config-drafts/save";
    private static final String COPY = "/internal/runtime/agent-config-drafts/copy";
    private static final String PUBLISH = "/internal/runtime/agent-config-drafts/publish";
    private static final String PUBLICATION_CONTEXT = "/internal/runtime/agent-config-drafts/publication-context";
    private static final String PUBLISH_APPROVED = "/internal/runtime/agent-config-drafts/publish-approved";
    private final RuntimeProxyClient runtime;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper json;

    public ResponseEntity<Object> context(String agentId, Long projectId, String projectCode, PlatformAuthenticatedSession actor) {
        byte[] body = command(agentId, projectId, projectCode, null, null, null);
        return call(() -> runtime.readAgentDraftContext(headers(CONTEXT, projectCode, actor, body), body));
    }

    public ResponseEntity<Object> versions(String agentId, Long projectId, String projectCode, PlatformAuthenticatedSession actor) {
        byte[] body = command(agentId, projectId, projectCode, null, null, null);
        return call(() -> runtime.readAgentConfigVersionsForProject(headers(VERSIONS, projectCode, actor, body), body));
    }

    public ResponseEntity<Object> save(String agentId, Long projectId, String projectCode, String revision,
                                       Map<String, Object> changes, PlatformAuthenticatedSession actor) {
        if (revision == null || !revision.matches("[a-f0-9]{64}") || changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("Agent draft revision and changes are required");
        }
        byte[] body = command(agentId, projectId, projectCode, revision, changes, null);
        return call(() -> runtime.saveAgentDraftIfUnchanged(headers(SAVE, projectCode, actor, body), body));
    }

    public ResponseEntity<Object> copy(String agentId, Long projectId, String projectCode, String revision,
                                       Long versionId, PlatformAuthenticatedSession actor) {
        byte[] body = versionCommand(agentId, projectId, projectCode, revision, versionId);
        return call(() -> runtime.copyAgentDraftIfUnchanged(headers(COPY, projectCode, actor, body), body));
    }

    public ResponseEntity<Object> publish(String agentId, Long projectId, String projectCode, String revision,
                                          Long versionId, PlatformAuthenticatedSession actor) {
        byte[] body = versionCommand(agentId, projectId, projectCode, revision, versionId);
        return call(() -> runtime.publishAgentDraftIfUnchanged(headers(PUBLISH, projectCode, actor, body), body));
    }

    public ResponseEntity<Object> publicationContext(String agentId, Long projectId, String projectCode, String revision,
                                                      Long versionId, PlatformAuthenticatedSession actor) {
        byte[] body = versionCommand(agentId, projectId, projectCode, revision, versionId);
        return call(() -> runtime.readAgentDraftPublicationContext(headers(PUBLICATION_CONTEXT, projectCode, actor, body), body));
    }

    public ResponseEntity<Object> publishApproved(String agentId, Long projectId, String projectCode, String revision,
                                                  Long versionId, Map<String, Long> workflowVersions, PlatformAuthenticatedSession actor) {
        if (workflowVersions == null) throw new IllegalArgumentException("Approved Workflow versions are required");
        byte[] base = versionCommand(agentId, projectId, projectCode, revision, versionId);
        byte[] body;
        try {
            var command = json.readValue(base, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            command.put("workflowVersions", workflowVersions);
            body = json.writeValueAsBytes(command);
        } catch (Exception invalid) { throw new IllegalArgumentException("Approved publication cannot be serialized", invalid); }
        return call(() -> runtime.publishApprovedAgentDraft(headers(PUBLISH_APPROVED, projectCode, actor, body), body));
    }

    private byte[] versionCommand(String agentId, Long projectId, String projectCode, String revision, Long versionId) {
        if (revision == null || !revision.matches("[a-f0-9]{64}") || versionId == null || versionId < 1) {
            throw new IllegalArgumentException("Agent draft revision and config version are required");
        }
        return command(agentId, projectId, projectCode, revision, null, versionId);
    }

    private byte[] command(String agentId, Long projectId, String projectCode, String revision,
                           Map<String, Object> changes, Long versionId) {
        if (agentId == null || agentId.isBlank() || agentId.length() > 32 || agentId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid Agent id");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentId", agentId); body.put("projectId", projectId); body.put("projectCode", projectCode);
        if (revision != null) body.put("expectedRevision", revision);
        if (changes != null) body.put("changes", changes);
        if (versionId != null) body.put("configVersionId", versionId);
        try { return json.writeValueAsBytes(body); }
        catch (Exception invalid) { throw new IllegalArgumentException("Agent draft command cannot be serialized", invalid); }
    }

    private Map<String, String> headers(String path, String projectCode, PlatformAuthenticatedSession actor, byte[] body) {
        if (actor == null || actor.user() == null || actor.user().getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform session is required");
        }
        return signer.sign("POST", path, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                projectCode, String.valueOf(actor.user().getId()), body);
    }

    private ResponseEntity<Object> call(Supplier<ResponseEntity<Object>> action) {
        try { return action.get(); }
        catch (FeignException failure) {
            if (failure.status() == 409) return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "AGENT_DRAFT_CHANGED", "message", "Agent 草稿或所属项目已变化，请重新读取后再操作"));
            if (failure.status() == 404) return ResponseEntity.notFound().build();
            if (failure.status() == 400) {
                String code = "AGENT_DRAFT_INVALID";
                try {
                    var body = json.readTree(failure.contentUTF8());
                    if ("BUSINESS_METHOD_WORKFLOW_RISK_DOWNGRADE".equals(body.path("code").asText())) {
                        code = "BUSINESS_METHOD_WORKFLOW_RISK_DOWNGRADE";
                    }
                } catch (Exception ignored) { /* Unknown upstream responses stay generic. */ }
                return ResponseEntity.badRequest().body(Map.of("code", code));
            }
            throw failure;
        }
    }
}
