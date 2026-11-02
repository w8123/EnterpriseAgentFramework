package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact-body project-key commands; no raw headers, caller-supplied actor or legacy write fallback. */
@Service
@RequiredArgsConstructor
public class ControlAiCodingAgentDraftGateway {
    private static final String ROOT = "/internal/runtime/project-agent-config-drafts/";
    private final RuntimeProxyClient runtime;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper json;

    public Map<String, Object> context(Long projectId, String projectCode, String agentId) {
        return invoke("context", projectId, projectCode, agentId, null, null, null);
    }
    public Map<String, Object> versions(Long projectId, String projectCode, String agentId) {
        return invoke("versions", projectId, projectCode, agentId, null, null, null);
    }
    public Map<String, Object> save(Long projectId, String projectCode, String agentId, String revision, Map<String, Object> changes) {
        requireRevision(revision);
        if (changes == null || changes.isEmpty()) throw invalid();
        return invoke("save", projectId, projectCode, agentId, revision, changes, null);
    }
    public Map<String, Object> publish(Long projectId, String projectCode, String agentId, String revision, Long configVersionId) {
        requireRevision(revision);
        if (configVersionId == null || configVersionId < 1) throw invalid();
        return invoke("publish", projectId, projectCode, agentId, revision, null, configVersionId);
    }

    private Map<String, Object> invoke(String action, Long projectId, String code, String id, String revision, Map<String, Object> changes, Long versionId) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
                || !(attributes.getRequest().getAttribute(VerifiedAiCodingProject.REQUEST_ATTRIBUTE) instanceof VerifiedAiCodingProject identity)
                || !identity.projectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "verified AI Coding project is required");
        }
        if (code == null || code.isBlank() || code.length() > 64 || code.chars().anyMatch(Character::isISOControl)
                || id == null || id.isBlank() || id.length() > 32 || id.chars().anyMatch(Character::isISOControl)) throw invalid();
        var command = new LinkedHashMap<String, Object>();
        command.put("agentId", id); command.put("projectId", projectId); command.put("projectCode", code);
        if (revision != null) command.put("expectedRevision", revision);
        if (changes != null) command.put("changes", changes);
        if (versionId != null) command.put("configVersionId", versionId);
        byte[] body;
        try { body = json.writeValueAsBytes(command); }
        catch (Exception malformed) { throw invalid(); }
        if (body.length > 512 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Agent draft command too large");
        Map<String, String> headers = signer.sign("POST", ROOT + action, InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL,
                code, identity.actorId(), body);
        ResponseEntity<Object> response;
        try {
            response = switch (action) {
                case "context" -> runtime.projectAgentDraftContext(headers, body);
                case "versions" -> runtime.projectAgentConfigVersions(headers, body);
                case "save" -> runtime.saveProjectAgentDraft(headers, body);
                case "publish" -> runtime.publishProjectAgentDraft(headers, body);
                default -> throw invalid();
            };
        } catch (FeignException failure) {
            int status = failure.status();
            throw new ResponseStatusException(status >= 400 && status < 500
                    ? org.springframework.http.HttpStatusCode.valueOf(status) : HttpStatus.BAD_GATEWAY,
                    status == 409 ? "Agent changed; read context before retrying"
                            : "Runtime Agent result is unconfirmed; read current state before retrying");
        }
        return checkedResponse(json, action, projectId, code, id, versionId, response);
    }

    static Map<String, Object> checkedResponse(ObjectMapper json, String action, Long projectId, String code,
                                               String id, Long versionId, ResponseEntity<Object> response) {
        if (response == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Runtime Agent response is unavailable");
        if (!response.getStatusCode().is2xxSuccessful()) throw new ResponseStatusException(response.getStatusCode(), "Runtime Agent command was refused");
        JsonNode view = json.valueToTree(response.getBody());
        if (action.equals("versions")) {
            if (view == null || !id.equals(view.path("agentId").asText()) || !view.path("projectId").isIntegralNumber()
                    || !view.path("projectId").canConvertToLong() || view.path("projectId").longValue() != projectId
                    || !code.equals(view.path("projectCode").asText()) || !view.path("versions").isArray())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Runtime Agent versions do not match the requested project");
            for (var version : view.path("versions")) {
                if (!id.equals(version.path("agentId").asText()) || !version.path("id").isIntegralNumber()
                        || !version.path("id").canConvertToLong() || version.path("id").asLong() < 1)
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Runtime returned another Agent's config version");
            }
            return json.convertValue(view, new TypeReference<LinkedHashMap<String, Object>>() { });
        }
        if (view == null || !id.equals(view.path("agentId").asText()) || !view.path("projectId").isIntegralNumber()
                || !view.path("projectId").canConvertToLong() || view.path("projectId").longValue() != projectId
                || !code.equals(view.path("projectCode").asText()) || !view.path("revision").asText().matches("[a-f0-9]{64}")
                || !view.has("base") || !view.path("base").isNull() && !view.path("base").isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Runtime Agent context is incomplete or belongs to another project");
        }
        JsonNode base = view.path("base");
        if (!base.isNull()) {
            if (!base.path("id").isIntegralNumber() || !base.path("id").canConvertToLong() || base.path("id").asLong() < 1
                    || !id.equals(base.path("agentId").asText())
                    || !java.util.Set.of("DRAFT", "ACTIVE").contains(base.path("status").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Runtime Agent base does not match the requested Agent");
            }
        }
        if (!action.equals("context")) {
            if (base.isNull() || !(action.equals("save") ? "DRAFT" : "ACTIVE").equals(base.path("status").asText())
                    || action.equals("publish") && base.path("id").longValue() != versionId) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Runtime Agent write result is unconfirmed; read current state before retrying");
            }
        }
        return json.convertValue(view, new TypeReference<LinkedHashMap<String, Object>>() { });
    }
    private void requireRevision(String revision) { if (revision == null || !revision.matches("[a-f0-9]{64}")) throw invalid(); }
    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid project Agent draft command"); }
}
