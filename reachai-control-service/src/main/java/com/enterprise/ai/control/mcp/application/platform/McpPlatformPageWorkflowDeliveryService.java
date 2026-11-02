package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPageWorkflowDeliveryPort;
import com.fasterxml.jackson.core.type.TypeReference;
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
 * Delivering a page Workflow publishes it and, with an Agent, a new live Agent config, so it goes to owner review.
 * The review freezes the exact delivery request and the applied engineering result it comes from; a newer applied
 * result or a different request refuses execution, and a lost result is reported as UNKNOWN rather than resent.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformPageWorkflowDeliveryService {
    public static final String STATE_CHANGED = "PAGE_WORKFLOW_DELIVERY_CHANGED";
    private static final List<String> COMMAND = List.of("pageKey", "version", "agentId", "modelInstanceId", "replaceWorkflowId");
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private final McpPlatformPageWorkflowDeliveryPort deliveries;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> request(ConnectionIdentity actor, String projectCode, String taskId, Map<String, Object> command, String invocationId) {
        Map<String, Object> frozen;
        try {
            frozen = snapshot(actor, projectCode, taskId, command);
        } catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.PAGE_WORKFLOW_DELIVERY, subject(taskId), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.PAGE_WORKFLOW_DELIVERY);
        Map<String, Object> frozen = claim.arguments();
        String projectCode;
        String taskId;
        Map<String, Object> command;
        try {
            projectCode = String.valueOf(frozen.get("projectCode"));
            taskId = String.valueOf(frozen.get("taskId"));
            if (!subject(taskId).equals(claim.subject())) throw changed();
            command = json.convertValue(frozen.get("command"), MAP);
            Map<String, Object> latest = snapshot(actor, projectCode, taskId, command);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try {
            response = deliveries.deliver(actor.session(), projectCode, taskId, command);
        } catch (ResponseStatusException definite) {
            if (!definite.getStatusCode().is4xxClientError()) return unknown(actor, approvalId);
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PAGE_WORKFLOW_DELIVERY_REJECTED");
            return refusal(definite);
        } catch (IllegalArgumentException rejected) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PAGE_WORKFLOW_DELIVERY_REJECTED");
            return refusal(rejected);
        } catch (RuntimeException uncertain) {
            // Includes "Runtime did not confirm Workflow delivery": the Workflow may already be published.
            return unknown(actor, approvalId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PAGE_WORKFLOW_DELIVERY_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknown(actor, approvalId);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, String projectCode, String taskId, Map<String, Object> command) {
        if (projectCode == null || !projectCode.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                || taskId == null || !taskId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (String field : COMMAND) {
            Object value = command == null ? null : command.get(field);
            if (value != null && !(value instanceof String)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            normalized.put(field, value == null || ((String) value).isBlank() ? null : ((String) value).strip());
        }
        if (command != null && !COMMAND.containsAll(command.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> plan = deliveries.plan(actor.session(), projectCode, taskId, normalized);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("projectCode", projectCode); result.put("taskId", taskId);
        result.put("command", normalized); result.put("plan", plan);
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private static String subject(String taskId) { return "PAGE_WORKFLOW_TASK:" + taskId; }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "page Workflow delivery changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        if (failure instanceof IllegalStateException notReady) {
            return ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED, "message", String.valueOf(notReady.getMessage())));
        }
        if (failure instanceof IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(invalid.getMessage())));
        }
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof ClassCastException || failure instanceof NullPointerException ? 400 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId);
        body.put("message", "交付结果未确认，Workflow 可能已发布。请查询原审批并用 reachai_page_workbench_read 查看已发布 Workflow；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
