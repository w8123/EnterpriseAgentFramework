package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformHttpConnectionPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformHttpConnectionPort.Change;
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
 * An HTTP API connection decides which address an API call goes to and which stored credential it carries, so pointing
 * it elsewhere could hand that credential to another host. Saving the API's own connection or the project default, and
 * removing the override, go to owner review and are written once at the revision the owner reviewed.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformHttpConnectionService {
    public static final String STATE_CHANGED = "HTTP_API_CONNECTION_CHANGED";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final List<String> FIELDS = List.of("origin", "authMode", "credentialRef");
    // Health and preview move on their own; what the owner approves is where calls go and with which credential.
    private static final List<String> FROZEN = List.of("qualifiedName", "projectId", "projectCode", "environment", "origin", "authMode",
            "credentialRef", "credentialName", "credentialRevision", "connectionSource", "overrideActive", "overrideRevision");
    private static final List<String> FROZEN_DEFAULT = List.of("origin", "authMode", "credentialRef", "credentialName", "revision");
    private final McpPlatformHttpConnectionPort connections;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> request(ConnectionIdentity actor, Change change, long apiId, Map<String, Object> fields, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, change, apiId, fields); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.HTTP_API_CONNECTION_CHANGE, "HTTP_API:" + apiId, frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.HTTP_API_CONNECTION_CHANGE);
        var frozen = claim.arguments();
        Change change;
        long apiId;
        Map<String, Object> body;
        try {
            change = Change.valueOf(String.valueOf(frozen.get("operation")));
            apiId = ((Number) frozen.get("apiId")).longValue();
            if (!("HTTP_API:" + apiId).equals(claim.subject())) throw changed();
            Map<String, Object> request = frozen.get("request") == null ? null : json.convertValue(frozen.get("request"), MAP);
            var latest = snapshot(actor, change, apiId, request);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
            body = new LinkedHashMap<>();
            if (latest.get("after") instanceof Map<?, ?> after) after.forEach((key, value) -> body.put(String.valueOf(key), value));
            body.put("expectedRevision", latest.get("expectedRevision"));
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try { response = connections.change(actor.session(), change, apiId, body); }
        catch (ResponseStatusException definite) {
            if (!definite.getStatusCode().is4xxClientError()) return unknown(actor, approvalId, apiId);
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_HTTP_API_CONNECTION_REJECTED");
            return ResponseEntity.status(definite.getStatusCode()).build();
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId, apiId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_HTTP_API_CONNECTION_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknown(actor, approvalId, apiId);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Change change, long apiId, Map<String, Object> input) {
        if (apiId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> request = input == null ? Map.of() : input;
        var response = connections.connection(actor.session(), apiId);
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
        }
        JsonNode view = json.valueToTree(response.getBody());
        Map<String, Object> before = new LinkedHashMap<>();
        for (String field : FROZEN) before.put(field, json.convertValue(view.get(field), Object.class));
        Map<String, Object> projectDefault = null;
        if (view.path("projectDefault").isObject()) {
            projectDefault = new LinkedHashMap<>();
            for (String field : FROZEN_DEFAULT) projectDefault.put(field, json.convertValue(view.path("projectDefault").get(field), Object.class));
        }
        before.put("projectDefault", projectDefault);
        Map<String, Object> after = null;
        Object expectedRevision;
        switch (change) {
            case SAVE_OVERRIDE -> {
                after = merged(Boolean.TRUE.equals(before.get("overrideActive")) ? before : Map.of(), request);
                expectedRevision = before.get("overrideRevision");
            }
            case SAVE_PROJECT_DEFAULT -> {
                after = merged(projectDefault == null ? Map.of() : projectDefault, request);
                expectedRevision = projectDefault == null ? null : projectDefault.get("revision");
            }
            default -> {
                if (!request.isEmpty() || !Boolean.TRUE.equals(before.get("overrideActive")) || before.get("overrideRevision") == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                }
                expectedRevision = before.get("overrideRevision");
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", change.name()); result.put("apiId", apiId);
        result.put("request", request.isEmpty() ? null : request);
        result.put("before", before); result.put("after", after); result.put("expectedRevision", expectedRevision);
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private static Map<String, Object> merged(Map<String, Object> current, Map<String, Object> input) {
        if (input.isEmpty() || !Set.copyOf(FIELDS).containsAll(input.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> after = new LinkedHashMap<>();
        for (String field : FIELDS) {
            Object value = input.containsKey(field) ? input.get(field) : current.get(field);
            if (value != null && !(value instanceof String)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            after.put(field, value);
        }
        return after;
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "HTTP API connection changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 400 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, long apiId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId); body.put("apiId", apiId);
        body.put("message", "连接修改结果未确认，请查询原审批并用 reachai_http_api_connection_get 重新读取；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
