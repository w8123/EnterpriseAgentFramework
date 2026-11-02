package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPageAssistantSyncPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Syncing a Page Assistant publishes a new ACTIVE page copilot config, so an AI request freezes the target, its
 * revision and the Workflow's live version for the owner's review and runs once.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformPageAssistantSyncService {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final List<String> CONTEXT_FIELDS = List.of("projectId", "projectCode", "workflowId", "workflowKeySlug",
            "workflowName", "workflowActiveVersionId", "targetExists", "agentId", "agentKeySlug", "activeConfigVersionNo",
            "activeModelInstanceId", "attached", "attachedWorkflowVersionId");
    private final McpPlatformPageAssistantSyncPort sync;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> context(ConnectionIdentity actor, String workflowId) {
        return sync.context(actor.session(), workflowId);
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, String workflowId, String baseRevision, boolean createTarget,
                                     String modelInstanceId, String replaceWorkflowId, String invocationId) {
        var response = sync.context(actor.session(), workflowId);
        if (response == null) return ResponseEntity.status(503).build();
        if (!response.getStatusCode().is2xxSuccessful()) return response;
        Map<String, Object> frozen;
        try { frozen = snapshot(response.getBody(), workflowId, baseRevision, createTarget, modelInstanceId, replaceWorkflowId); }
        catch (ResponseStatusException changed) { return refusal(changed); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.PAGE_ASSISTANT_SYNC, "WORKFLOW:" + workflowId, frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.PAGE_ASSISTANT_SYNC);
        var frozen = claim.arguments();
        String workflowId;
        Map<String, Object> command = new LinkedHashMap<>();
        try {
            workflowId = (String) frozen.get("workflowId");
            if (workflowId == null || !("WORKFLOW:" + workflowId).equals(claim.subject())) throw changed();
            String revision = (String) frozen.get("baseRevision");
            boolean createTarget = Boolean.TRUE.equals(frozen.get("createTarget"));
            String model = (String) frozen.get("modelInstanceId"), replace = (String) frozen.get("replaceWorkflowId");
            var current = sync.context(actor.session(), workflowId);
            if (current == null || !current.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(current == null ? HttpStatus.SERVICE_UNAVAILABLE : current.getStatusCode());
            }
            var latest = snapshot(current.getBody(), workflowId, revision, createTarget, model, replace);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
            if (createTarget) command.put("createTarget", true); else command.put("baseRevision", revision);
            if (model != null) command.put("modelInstanceId", model);
            if (replace != null) command.put("replaceWorkflowId", replace);
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try { response = sync.attach(actor.session(), workflowId, command); }
        catch (RuntimeException failure) {
            var denied = refusal(failure);
            if (denied.getStatusCode().is4xxClientError()) {
                approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_SYNC_REJECTED");
                return denied;
            }
            return unknown(actor, approvalId, workflowId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_SYNC_REJECTED");
            return response;
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful() || !(response.getBody() instanceof Map<?, ?> result)
                || !workflowId.equals(result.get("workflowId")) || !Boolean.TRUE.equals(result.get("published"))
                || Boolean.TRUE.equals(frozen.get("targetExists")) && !Objects.equals(frozen.get("agentId"), result.get("agentId"))) {
            return unknown(actor, approvalId, workflowId);
        }
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(Object body, String workflowId, String baseRevision, boolean createTarget,
                                         String modelInstanceId, String replaceWorkflowId) {
        Map<String, Object> context = json.convertValue(body, MAP);
        if (context == null || !Integer.valueOf(1).equals(context.get("contractVersion"))
                || !Objects.equals(workflowId, context.get("workflowId")) || !"ACTIVE".equals(context.get("workflowStatus"))
                || !(context.get("workflowActiveVersionId") instanceof Number)
                || !(context.get("projectCode") instanceof String code) || code.isBlank()) throw changed();
        boolean exists = Boolean.TRUE.equals(context.get("targetExists"));
        // A request is valid only for the state that was read: the existing target's revision, or initialization.
        if (exists ? createTarget || baseRevision == null || !baseRevision.equals(context.get("revision"))
                : !createTarget || baseRevision != null) throw changed();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1);
        for (String field : CONTEXT_FIELDS) result.put(field, context.get(field));
        result.put("baseRevision", baseRevision); result.put("createTarget", createTarget);
        result.put("modelInstanceId", modelInstanceId); result.put("replaceWorkflowId", replaceWorkflowId);
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Page copilot or Page Assistant changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof FeignException remote && remote.status() > 0 ? remote.status() : 503;
        return ResponseEntity.status(status).body(Map.of("code", status == 409 ? "AGENT_DRAFT_CHANGED" : "PAGE_COPILOT_SYNC_REJECTED"));
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, String workflowId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "approvalId", approvalId, "workflowId", workflowId,
                "message", "同步结果未确认，请查询原审批并重新读取页面副驾驶状态；不要新建申请直接重试。"));
    }
}
