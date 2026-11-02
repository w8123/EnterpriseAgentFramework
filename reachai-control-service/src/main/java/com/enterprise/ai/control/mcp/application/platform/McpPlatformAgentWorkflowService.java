package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAgentWorkflowPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformReadPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Preparing a knowledge Q&A Workflow without attaching it creates a new Workflow nothing uses yet, so it runs directly.
 * Moving an Agent's live config to the newest Workflow versions, and attaching a knowledge Q&A Workflow to the Agent,
 * both publish a new live Agent config, so they go to owner review. The review freezes the Agent's live Workflow pins;
 * if the live config or any pinned Workflow changes before execution the request is refused, and a lost result is
 * reported as UNKNOWN instead of being sent again.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformAgentWorkflowService {
    public static final String STATE_CHANGED = "AGENT_WORKFLOW_PUBLICATION_CHANGED";
    public enum Operation { PUBLISH_LATEST, KNOWLEDGE_QA }
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private final McpPlatformReadPort reads;
    private final McpPlatformAgentWorkflowPort agents;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> prepareKnowledgeQa(ConnectionIdentity actor, String agentId, String knowledgeBaseCode, String knowledgeBaseName,
                                                String modelInstanceId) {
        return PlatformAiDirectWrites.once(() -> agents.knowledgeQa(actor.session(), agentId, false, knowledgeBaseCode, knowledgeBaseName, modelInstanceId),
                "知识问答 Workflow 是否已创建未确认，请先查看项目里的 Workflow 再决定；不要直接重试。");
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, Operation operation, String agentId, String knowledgeBaseCode,
                                     String knowledgeBaseName, String modelInstanceId, String invocationId) {
        Map<String, Object> frozen;
        try {
            if (operation == Operation.KNOWLEDGE_QA && (knowledgeBaseCode == null || knowledgeBaseCode.isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            frozen = snapshot(actor, operation, agentId, knowledgeBaseCode, knowledgeBaseName, modelInstanceId);
        } catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.AGENT_WORKFLOW_PUBLISH, subject(agentId), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.AGENT_WORKFLOW_PUBLISH);
        Map<String, Object> frozen = claim.arguments();
        Operation operation;
        String agentId;
        long activeConfigVersionId;
        try {
            operation = Operation.valueOf(String.valueOf(frozen.get("operation")));
            agentId = String.valueOf(frozen.get("agentId"));
            if (!subject(agentId).equals(claim.subject())) throw changed();
            Map<String, Object> latest = snapshot(actor, operation, agentId, (String) frozen.get("knowledgeBaseCode"),
                    (String) frozen.get("knowledgeBaseName"), (String) frozen.get("modelInstanceId"));
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
            activeConfigVersionId = ((Number) frozen.get("activeConfigVersionId")).longValue();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try {
            response = operation == Operation.PUBLISH_LATEST
                    ? agents.publishLatest(actor.session(), agentId, activeConfigVersionId)
                    : agents.knowledgeQa(actor.session(), agentId, true, (String) frozen.get("knowledgeBaseCode"),
                            (String) frozen.get("knowledgeBaseName"), (String) frozen.get("modelInstanceId"));
        } catch (ResponseStatusException definite) {
            if (!definite.getStatusCode().is4xxClientError()) return unknown(actor, approvalId);
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PUBLISH_REJECTED");
            return refusal(definite);
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PUBLISH_REJECTED");
            return response.getStatusCode().value() == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(response.getStatusCode()).build();
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknown(actor, approvalId);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Operation operation, String agentId, String knowledgeBaseCode,
                                         String knowledgeBaseName, String modelInstanceId) {
        if (agentId == null || !agentId.matches("[A-Za-z0-9_-]{1,64}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        ResponseEntity<?> response = reads.getAgentWorkflowFreshness(actor.session(), agentId);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new ResponseStatusException(response == null ? HttpStatus.BAD_GATEWAY : response.getStatusCode());
        }
        Map<String, Object> freshness = json.convertValue(response.getBody(), MAP);
        if (freshness == null || !agentId.equals(freshness.get("agentId")) || !(freshness.get("activeConfigVersionId") instanceof Number active)) {
            // Both operations copy the live config, so an Agent that has never been published cannot use them.
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (operation == Operation.PUBLISH_LATEST && !(freshness.get("staleCount") instanceof Number stale && stale.intValue() > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent already uses the newest Workflow versions");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", operation.name()); result.put("agentId", agentId);
        result.put("activeConfigVersionId", active.longValue());
        result.put("projectId", freshness.get("projectId")); result.put("projectCode", freshness.get("projectCode"));
        result.put("agentName", freshness.get("agentName"));
        result.put("workflows", freshness.get("tools"));
        if (operation == Operation.KNOWLEDGE_QA) {
            result.put("knowledgeBaseCode", knowledgeBaseCode.strip());
            result.put("knowledgeBaseName", knowledgeBaseName == null || knowledgeBaseName.isBlank() ? null : knowledgeBaseName.strip());
            result.put("modelInstanceId", modelInstanceId == null || modelInstanceId.isBlank() ? null : modelInstanceId.strip());
        }
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private static String subject(String agentId) { return "AGENT:" + agentId; }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Agent live config changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 400 : 503;
        if (status == 409) return ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED));
        if (status == 400 && failure instanceof ResponseStatusException denied && denied.getReason() != null) {
            return ResponseEntity.badRequest().body(Map.of("message", denied.getReason()));
        }
        return ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId);
        body.put("message", "Agent 发布结果未确认，请查询原审批并用 reachai_agent_workflow_freshness 重新读取；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
