package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAgentLifecyclePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformReadPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turning an Agent on or off decides whether people can use it, and deleting one cannot be undone, so both go to owner
 * review and run once against the Agent as it was reviewed.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformAgentLifecycleService {
    public static final String STATE_CHANGED = "AGENT_LIFECYCLE_CHANGED";
    public enum Change { ENABLE, DISABLE, DELETE }
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    // Draft edits and Workflow freshness move independently; what the owner approves is who the Agent serves and what runs.
    private static final java.util.List<String> FROZEN = java.util.List.of("id", "name", "projectId", "projectCode", "visibility",
            "allowedRolesJson", "enabled", "activeConfigVersionId", "runtimeType");
    private final McpPlatformReadPort reads;
    private final McpPlatformAgentLifecyclePort agents;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> request(ConnectionIdentity actor, Change change, String agentId, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, change, agentId); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.AGENT_LIFECYCLE_CHANGE, "AGENT:" + agentId, frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.AGENT_LIFECYCLE_CHANGE);
        var frozen = claim.arguments();
        Change change;
        String agentId;
        try {
            change = Change.valueOf(String.valueOf(frozen.get("operation")));
            agentId = (String) frozen.get("agentId");
            if (!("AGENT:" + agentId).equals(claim.subject())) throw changed();
            var latest = snapshot(actor, change, agentId);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try {
            response = change == Change.DELETE ? agents.delete(actor.session(), agentId)
                    : agents.setEnabled(actor.session(), agentId, change == Change.ENABLE);
        } catch (ResponseStatusException definite) {
            if (!definite.getStatusCode().is4xxClientError()) return unknown(actor, approvalId, agentId);
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_AGENT_REJECTED");
            return ResponseEntity.status(definite.getStatusCode()).build();
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId, agentId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_AGENT_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknown(actor, approvalId, agentId);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Change change, String agentId) {
        if (agentId == null || !agentId.matches("[A-Za-z0-9_.:-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var response = reads.getAgent(actor.session(), agentId);
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
        }
        JsonNode root = json.valueToTree(response.getBody());
        JsonNode agent = root.has("data") && root.path("data").isObject() ? root.path("data") : root;
        if (!agentId.equals(agent.path("id").asText())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        boolean enabled = agent.path("enabled").asBoolean(true);
        if (change == Change.ENABLE && enabled || change == Change.DISABLE && !enabled) throw changed();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", change.name()); result.put("agentId", agentId);
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String field : FROZEN) summary.put(field, json.convertValue(agent.get(field), Object.class));
        result.put("agent", summary);
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Agent changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 400 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, String agentId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId); body.put("agentId", agentId);
        body.put("message", "Agent 启停或删除结果未确认，请查询原审批并重新读取 Agent；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
