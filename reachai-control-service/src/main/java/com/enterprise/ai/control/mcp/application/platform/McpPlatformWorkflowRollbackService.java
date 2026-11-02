package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowRollbackPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rolling back replaces the live Workflow version, so an AI request freezes the target version's graph, the current
 * live version and the working-copy revision for the owner's review; the Studio rollback then runs once.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformWorkflowRollbackService {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private final McpPlatformReadPort reads;
    private final McpPlatformWorkflowRollbackPort rollbacks;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> request(ConnectionIdentity actor, String workflowId, long versionId, String baseRevision, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, workflowId, versionId, baseRevision); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.WORKFLOW_ROLLBACK, "WORKFLOW:" + workflowId, frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.WORKFLOW_ROLLBACK);
        var frozen = claim.arguments();
        String workflowId, revision;
        long versionId;
        try {
            workflowId = (String) frozen.get("workflowId"); revision = (String) frozen.get("baseRevision");
            versionId = ((Number) frozen.get("versionId")).longValue();
            if (!("WORKFLOW:" + workflowId).equals(claim.subject())) throw changed();
            var latest = snapshot(actor, workflowId, versionId, revision);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try { response = rollbacks.rollback(actor.session(), workflowId, versionId, revision); }
        catch (RuntimeException failure) {
            var denied = refusal(failure);
            if (denied.getStatusCode().is4xxClientError()) {
                approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_ROLLBACK_REJECTED");
                return denied;
            }
            return unknown(actor, approvalId, workflowId, versionId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_ROLLBACK_REJECTED");
            return response;
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful() || !(response.getBody() instanceof Map<?, ?> result)
                || !(result.get("id") instanceof Number id) || id.longValue() != versionId
                || !workflowId.equals(result.get("workflowId")) || !"ACTIVE".equals(result.get("status"))) {
            return unknown(actor, approvalId, workflowId, versionId);
        }
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, String workflowId, long versionId, String baseRevision) {
        if (baseRevision == null || baseRevision.isBlank() || versionId < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> copy = body(reads.getWorkflowWorkingCopy(actor.session(), workflowId));
        if (!workflowId.equals(copy.get("id")) && !workflowId.equals(copy.get("workflowId"))) throw changed();
        if (!baseRevision.equals(copy.get("revision"))) throw changed();
        Object versions = reads.listWorkflowVersions(actor.session(), workflowId).getBody();
        if (!(versions instanceof List<?> list)) throw changed();
        Map<String, Object> target = null;
        Object activeVersionId = null;
        for (Object item : list) {
            Map<String, Object> version = json.convertValue(item, MAP);
            if (version == null || !workflowId.equals(version.get("workflowId"))) continue;
            if ("ACTIVE".equals(version.get("status"))) activeVersionId = version.get("id");
            if (version.get("id") instanceof Number id && id.longValue() == versionId) target = version;
        }
        if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if ("ACTIVE".equals(target.get("status"))) throw changed();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("workflowId", workflowId);
        result.put("workflowName", copy.get("name")); result.put("projectId", copy.get("projectId")); result.put("projectCode", copy.get("projectCode"));
        result.put("baseRevision", baseRevision); result.put("versionId", versionId); result.put("targetVersion", target.get("version"));
        result.put("targetPublishedAt", target.get("publishedAt")); result.put("targetPublishedBy", target.get("publishedBy"));
        result.put("targetGraphSha256", sha256(target.get("graphSpecSnapshotJson")));
        result.put("activeVersionId", activeVersionId);
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private Map<String, Object> body(ResponseEntity<?> response) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
        }
        Map<String, Object> body = json.convertValue(response.getBody(), MAP);
        if (body == null) throw changed();
        return body;
    }

    private static String sha256(Object graph) {
        if (!(graph instanceof String text) || text.isBlank()) throw changed();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Workflow or its versions changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof FeignException remote && remote.status() > 0 ? remote.status()
                : failure instanceof IllegalArgumentException ? 400 : 503;
        return ResponseEntity.status(status).body(Map.of("code", status == 409 ? "WORKFLOW_PUBLICATION_CHANGED" : "PLATFORM_PUBLISH_REJECTED"));
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, String workflowId, long versionId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "approvalId", approvalId, "workflowId", workflowId,
                "versionId", versionId, "message", "回滚结果未确认，请查询原审批和 Workflow 发布版本；不要新建申请直接重试。"));
    }
}
