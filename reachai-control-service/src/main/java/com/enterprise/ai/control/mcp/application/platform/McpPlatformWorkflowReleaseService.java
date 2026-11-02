package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowReleasePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class McpPlatformWorkflowReleaseService {
    private final McpPlatformWorkflowReleasePort releases;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> request(ConnectionIdentity actor, String id, String revision, String version, String note, String invocationId) {
        var response = releases.context(actor.session(), id, revision, version, note);
        if (response == null) return ResponseEntity.status(503).build();
        if (!response.getStatusCode().is2xxSuccessful()) return response;
        var frozen = snapshot(response.getBody(), id, revision, version, note);
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.WORKFLOW_PUBLISH, "WORKFLOW:" + id, frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.WORKFLOW_PUBLISH);
        var frozen = claim.arguments();
        String id, revision, version, note, digest;
        Long projectId;
        String projectCode;
        try {
            id = (String) frozen.get("workflowId"); revision = (String) frozen.get("baseRevision");
            version = (String) frozen.get("version"); note = (String) frozen.get("note"); digest = (String) frozen.get("publicationDigest");
            projectId = number(frozen.get("projectId")); projectCode = (String) frozen.get("projectCode");
            if (!("WORKFLOW:" + id).equals(claim.subject())) throw changed();
            var current = releases.context(actor.session(), id, revision, version, note);
            if (current == null || !current.getStatusCode().is2xxSuccessful()) throw new ResponseStatusException(current == null ? HttpStatus.SERVICE_UNAVAILABLE : current.getStatusCode());
            var latest = snapshot(current.getBody(), id, revision, version, note);
            if (!Objects.equals(digest, latest.get("publicationDigest")) || !Objects.equals(projectId, number(latest.get("projectId")))
                    || !Objects.equals(projectCode, latest.get("projectCode"))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try { response = releases.publish(actor.session(), id, revision, version, note, projectId, projectCode, digest); }
        catch (RuntimeException failure) {
            var denied = refusal(failure);
            if (denied.getStatusCode().is4xxClientError()) {
                approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PUBLISH_REJECTED"); return denied;
            }
            return unknown(actor, approvalId, id, version);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PUBLISH_REJECTED"); return response;
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful() || !(response.getBody() instanceof Map<?, ?> result)
                || !id.equals(result.get("workflowId")) || !version.equals(result.get("version")) || !"ACTIVE".equals(result.get("status"))
                || !(result.get("id") instanceof Number) || !Objects.equals(frozen.get("publishedGraph"), result.get("graphSpecSnapshotJson"))) {
            return unknown(actor, approvalId, id, version);
        }
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(Object body, String id, String revision, String version, String note) {
        if (!(body instanceof Map<?, ?> snapshot) || !Integer.valueOf(1).equals(snapshot.get("contractVersion"))
                || !Objects.equals(id, snapshot.get("workflowId")) || !Objects.equals(revision, snapshot.get("baseRevision"))
                || !Objects.equals(version, snapshot.get("version")) || !Objects.equals(note, snapshot.get("note"))
                || !(snapshot.get("publicationDigest") instanceof String digest) || !digest.matches("[0-9a-f]{64}")
                || !(snapshot.get("definition") instanceof Map<?, ?> definition) || !Objects.equals(id, definition.get("id"))
                || !(snapshot.get("publishedGraph") instanceof String graph) || graph.isBlank()
                || !snapshot.containsKey("projectId") || !snapshot.containsKey("projectCode") || !snapshot.containsKey("activeVersionId")
                || !Objects.equals(number(snapshot.get("projectId")), number(definition.get("projectId")))
                || !Objects.equals(snapshot.get("projectCode"), definition.get("projectCode"))) throw changed();
        Map<String, Object> result = json.convertValue(snapshot, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() { });
        number(result.get("activeVersionId"));
        return result;
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, String id, String version) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "approvalId", approvalId, "workflowId", id, "version", version,
                "message", "发布结果未确认，请查询原审批和 Workflow 发布版本，勿新建请求重试。"));
    }
    private static Long number(Object value) {
        if (value == null) return null;
        if (value instanceof Number) try { long id = Long.parseLong(value.toString()); if (id > 0) return id; } catch (NumberFormatException ignored) { }
        throw changed();
    }
    private static ResponseStatusException changed() { return new ResponseStatusException(HttpStatus.CONFLICT); }
    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof FeignException remote && remote.status() > 0 ? remote.status() : 503;
        return ResponseEntity.status(status).body(Map.of("code", status == 409 ? "WORKFLOW_PUBLICATION_CHANGED" : "PLATFORM_PUBLISH_REJECTED"));
    }
}
