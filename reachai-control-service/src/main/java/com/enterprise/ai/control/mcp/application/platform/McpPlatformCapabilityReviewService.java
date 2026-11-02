package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityReviewPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityReviewPort.Action;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applying a scanned capability change changes the contract Agents and Workflows call, ignoring it keeps the old one, and
 * rolling back undoes an applied change. Each goes to owner review and runs once against the change as it was reviewed.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformCapabilityReviewService {
    public static final String STATE_CHANGED = "CAPABILITY_CHANGE_STATE_CHANGED";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;
    private static final List<String> FROZEN = List.of("id", "snapshotId", "syncId", "projectCode", "qualifiedName", "name", "storageName",
            "changeType", "existingToolId", "fieldDiffJson", "impactJson", "reviewStatus", "rollbackAvailable");
    private final McpPlatformCapabilityReadPort reads;
    private final McpPlatformCapabilityReviewPort reviews;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> request(ConnectionIdentity actor, String projectCode, long diffItemId, Action action, String note, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, projectCode, diffItemId, action, note); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.CAPABILITY_CHANGE_REVIEW, subject(projectCode, diffItemId), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.CAPABILITY_CHANGE_REVIEW);
        var frozen = claim.arguments();
        String projectCode;
        long diffItemId;
        Action action;
        String note;
        try {
            projectCode = (String) frozen.get("projectCode");
            diffItemId = ((Number) frozen.get("diffItemId")).longValue();
            action = Action.valueOf(String.valueOf(frozen.get("action")));
            note = (String) frozen.get("note");
            if (!subject(projectCode, diffItemId).equals(claim.subject())) throw changed();
            var latest = snapshot(actor, projectCode, diffItemId, action, note);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try { response = reviews.review(actor.session(), projectCode, diffItemId, action, note); }
        catch (ResponseStatusException definite) {
            if (!definite.getStatusCode().is4xxClientError()) return unknown(actor, approvalId, projectCode, diffItemId);
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_CAPABILITY_REVIEW_REJECTED");
            return ResponseEntity.status(definite.getStatusCode()).build();
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId, projectCode, diffItemId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_CAPABILITY_REVIEW_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknown(actor, approvalId, projectCode, diffItemId);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, String projectCode, long diffItemId, Action action, String note) {
        if (projectCode == null || projectCode.isBlank() || diffItemId <= 0 || note != null && note.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        JsonNode item = find(actor, projectCode, diffItemId, action == Action.ROLLBACK ? "PROCESSED" : "PENDING");
        if (action == Action.ROLLBACK ? !item.path("rollbackAvailable").asBoolean(false) : !"PENDING".equals(item.path("reviewStatus").asText())) {
            throw changed();
        }
        Map<String, Object> change = new LinkedHashMap<>();
        for (String field : FROZEN) change.put(field, json.convertValue(item.get(field), Object.class));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("projectCode", projectCode); result.put("diffItemId", diffItemId);
        result.put("action", action.name()); result.put("note", note); result.put("change", change);
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private JsonNode find(ConnectionIdentity actor, String projectCode, long diffItemId, String state) {
        for (int page = 1; page <= MAX_PAGES; page++) {
            var response = reads.capabilityChanges(actor.session(), projectCode, state, "", page, PAGE_SIZE);
            if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
            }
            JsonNode records = json.valueToTree(response.getBody()).path("records");
            if (!records.isArray()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
            for (JsonNode record : records) {
                if (record.path("id").asLong() == diffItemId) {
                    if (!projectCode.equals(record.path("projectCode").asText(projectCode))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    return record;
                }
            }
            if (records.size() < PAGE_SIZE) break;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static String subject(String projectCode, long diffItemId) {
        return "CAPABILITY_CHANGE:" + projectCode + ":" + diffItemId;
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Capability change state changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 400 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, String projectCode, long diffItemId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId); body.put("projectCode", projectCode); body.put("diffItemId", diffItemId);
        body.put("message", "能力变更评审结果未确认，请查询原审批并用 reachai_capability_changes 重新读取；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
