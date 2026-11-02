package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformRunOpsPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformRunOpsPort.Operation;
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
import java.util.Set;

/**
 * Evaluation runs execute in Runtime's read-only Eval context, so creating datasets, versions and experiments is as
 * direct as in the console; an unconfirmed create is UNKNOWN and never resent. Replaying a run executes the published
 * Agent again with new input under the caller's identity, with whatever side effects its tools have, so it goes to
 * owner review and runs once.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformRunOpsService {
    public static final String STATE_CHANGED = "RUN_REPLAY_SOURCE_CHANGED";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final Set<Operation> CREATES = Set.of(Operation.EVAL_DATASET_CREATE, Operation.EVAL_DATASET_VERSION_CREATE,
            Operation.EVAL_DATASET_VERSION_FROM_TRACE, Operation.EVAL_EXPERIMENT_CREATE);
    private static final List<String> SOURCE_FIELDS = List.of("traceId", "agentId", "agentName", "agentConfigVersionId", "projectCode", "status");
    private final McpPlatformRunOpsPort runOps;
    private final McpPlatformReadPort reads;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> direct(ConnectionIdentity actor, Operation operation, String id, String secondId,
                                    Map<String, Object> query, Map<String, Object> body) {
        if (operation == Operation.REPLAY) throw new IllegalArgumentException("replay needs owner review");
        ResponseEntity<?> response;
        try { response = runOps.call(actor.session(), operation, id, secondId, query, body); }
        catch (ResponseStatusException | IllegalArgumentException definite) { throw definite; }
        catch (feign.FeignException remote) {
            if (remote.status() >= 400 && remote.status() < 500) throw remote;
            response = null;
        }
        if (CREATES.contains(operation) && (response == null || response.getStatusCode().is5xxServerError())) {
            return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN",
                    "message", "创建结果未确认，请先读取数据集或实验列表核对，不要直接再次创建。"));
        }
        return response == null ? ResponseEntity.status(503).build() : response;
    }

    public ResponseEntity<?> requestReplay(ConnectionIdentity actor, String traceId, String messageOverride, String sessionId, String invocationId) {
        if (messageOverride == null || messageOverride.isBlank() || messageOverride.length() > 8000) return ResponseEntity.badRequest().build();
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, traceId, messageOverride, sessionId); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.RUN_REPLAY, "RUN:" + traceId, frozen));
    }

    public ResponseEntity<?> executeReplay(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.RUN_REPLAY);
        var frozen = claim.arguments();
        String traceId;
        Map<String, Object> command;
        try {
            traceId = (String) frozen.get("traceId");
            if (!("RUN:" + traceId).equals(claim.subject())) throw changed();
            command = json.convertValue(frozen.get("command"), MAP);
            var latest = snapshot(actor, traceId, (String) command.get("messageOverride"), (String) command.get("sessionId"));
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try {
            response = runOps.call(actor.session(), Operation.REPLAY, traceId, null, Map.of(), command);
        } catch (ResponseStatusException rejected) {
            if (!rejected.getStatusCode().is4xxClientError()) return unknown(actor, approvalId, traceId);
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_RUN_REPLAY_REJECTED");
            return ResponseEntity.status(rejected.getStatusCode()).build();
        } catch (IllegalArgumentException rejected) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_RUN_REPLAY_REJECTED");
            return ResponseEntity.badRequest().build();
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId, traceId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_RUN_REPLAY_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknown(actor, approvalId, traceId);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, String traceId, String messageOverride, String sessionId) {
        if (traceId == null || !traceId.matches("[A-Za-z0-9_.:-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (sessionId != null && !sessionId.matches("[A-Za-z0-9_.:-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var detail = reads.getRunOpsDetail(actor.session(), traceId);
        if (detail == null || !detail.getStatusCode().is2xxSuccessful() || detail.getBody() == null) {
            throw new ResponseStatusException(detail == null ? HttpStatus.SERVICE_UNAVAILABLE : detail.getStatusCode());
        }
        JsonNode summary = json.valueToTree(detail.getBody()).path("summary");
        if (!traceId.equals(summary.path("traceId").asText())) throw changed();
        Map<String, Object> source = new LinkedHashMap<>();
        for (String field : SOURCE_FIELDS) source.put(field, json.convertValue(summary.get(field), Object.class));
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("messageOverride", messageOverride);
        command.put("sessionId", sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("traceId", traceId); result.put("source", source); result.put("command", command);
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Run changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof feign.FeignException remote && remote.status() > 0 ? remote.status()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 400 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, String traceId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId); body.put("traceId", traceId);
        body.put("message", "重放结果未确认，请查询原审批并在最近运行中查找这次重放；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
