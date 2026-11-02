package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformMemoryErasurePort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading a request and retrying the domains that did not finish run directly. Creating a request permanently erases
 * one person's memories in every domain, so it goes to owner review with the exact target frozen. The request key makes
 * the create replay-safe on the server, but a lost result is still reported as UNKNOWN rather than resent: a new
 * approval with the same request key returns the existing request instead of starting a second one.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformMemoryErasureService {
    /** The compliance console's explicit confirmation phrase for erasing every memory domain. */
    static final String CONFIRMATION = "ERASE_ALL_AGENT_MEMORY_DOMAINS";
    private static final List<String> FIELDS = List.of("tenantId", "runtimeUserId", "clientRequestId", "reasonCode", "referenceId");
    private final McpPlatformMemoryErasurePort erasures;
    private final PlatformAiApprovalService approvals;

    public ResponseEntity<?> get(ConnectionIdentity actor, String requestId) {
        return erasures.get(actor.session(), requestId);
    }

    public ResponseEntity<?> retry(ConnectionIdentity actor, String requestId) {
        return PlatformAiDirectWrites.once(() -> erasures.retry(actor.session(), requestId),
                "重试结果未确认，请先用 reachai_memory_erasure_get 查看请求状态；不要直接重试。");
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, Map<String, Object> command, String invocationId) {
        if (command == null || !CONFIRMATION.equals(command.get("confirmation"))) {
            return ResponseEntity.badRequest().body(Map.of("message", "confirmation 必须为 " + CONFIRMATION));
        }
        Map<String, Object> frozen = new LinkedHashMap<>();
        frozen.put("contractVersion", 1);
        for (String field : FIELDS) {
            Object value = command.get(field);
            if (!(value instanceof String text) || text.isBlank() || text.length() > 128) return ResponseEntity.badRequest().build();
            frozen.put(field, text.strip());
        }
        String key = (String) frozen.get("clientRequestId");
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) return ResponseEntity.badRequest().build();
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.MEMORY_ERASURE_REQUEST, subject(key), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.MEMORY_ERASURE_REQUEST);
        Map<String, Object> frozen = claim.arguments();
        Map<String, Object> command = new LinkedHashMap<>();
        try {
            if (((Number) frozen.get("contractVersion")).intValue() != 1
                    || !subject(String.valueOf(frozen.get("clientRequestId"))).equals(claim.subject())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            command.put("confirmation", CONFIRMATION);
            for (String field : FIELDS) command.put(field, String.valueOf(frozen.get(field)));
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return ResponseEntity.status(denied instanceof ResponseStatusException status ? status.getStatusCode() : HttpStatus.BAD_REQUEST).build();
        }
        ResponseEntity<?> response;
        try {
            response = erasures.create(actor.session(), command);
        } catch (ResponseStatusException definite) {
            if (!definite.getStatusCode().is4xxClientError()) return unknown(actor, approvalId);
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_MEMORY_ERASURE_REJECTED");
            return ResponseEntity.status(definite.getStatusCode()).build();
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_MEMORY_ERASURE_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknown(actor, approvalId);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private static String subject(String clientRequestId) { return "MEMORY_ERASURE:" + clientRequestId; }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId);
        body.put("message", "删除请求是否已创建未确认。用同一个 clientRequestId 重新申请审核时，服务端会返回已有请求而不会重复创建。");
        return ResponseEntity.accepted().body(body);
    }
}
