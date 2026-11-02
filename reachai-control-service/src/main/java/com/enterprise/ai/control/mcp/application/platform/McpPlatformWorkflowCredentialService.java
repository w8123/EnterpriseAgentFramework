package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowCredentialPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A new credential is unused until a connection or Workflow references it (and its reference is unique), so creating
 * one runs directly. Updating a credential changes how live calls authenticate or who may use it, and disabling one
 * stops every call that uses it, so both go to owner review. A secret is never frozen: the approval keeps a salted
 * fingerprint and the executing connection must supply the same secret again. A lost result is never resent.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformWorkflowCredentialService {
    public static final String STATE_CHANGED = "WORKFLOW_CREDENTIAL_CHANGED";
    public enum Change { UPDATE, DISABLE }
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final List<String> FROZEN = List.of("id", "credentialRef", "name", "type", "projectId", "projectCode", "scope", "status",
            "secretPreview", "updatedAt");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final McpPlatformWorkflowCredentialPort credentials;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> list(ConnectionIdentity actor, Long projectId, String projectCode) {
        return credentials.list(actor.session(), projectId, projectCode);
    }

    public ResponseEntity<?> create(ConnectionIdentity actor, Map<String, Object> body) {
        return PlatformAiDirectWrites.once(() -> credentials.create(actor.session(), body),
                "凭据创建结果未确认，请先用 reachai_workflow_credentials 查看再决定；不要直接重试。");
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, Change change, long id, Long projectId, String projectCode, Map<String, Object> fields,
                                     Map<String, Object> secret, String invocationId) {
        Map<String, Object> frozen;
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            if (fields != null) request.putAll(fields);
            // The stored secret is shaped by its type, so a type change has to bring a matching secret.
            if (request.containsKey("type") && secret == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            if (secret != null) {
                if (change != Change.UPDATE || secret.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                byte[] salt = new byte[16];
                RANDOM.nextBytes(salt);
                String saltHex = HexFormat.of().formatHex(salt);
                request.put("secretSalt", saltHex);
                request.put("secretSha256", fingerprint(saltHex, secret));
            }
            frozen = snapshot(actor, change, id, projectId, projectCode, request);
        } catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.WORKFLOW_CREDENTIAL_CHANGE, subject(id), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId, Map<String, Object> secret) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.WORKFLOW_CREDENTIAL_CHANGE);
        var frozen = claim.arguments();
        Change change;
        long id;
        Map<String, Object> after;
        try {
            change = Change.valueOf(String.valueOf(frozen.get("operation")));
            id = ((Number) frozen.get("credentialId")).longValue();
            if (!subject(id).equals(claim.subject())) throw changed();
            Map<String, Object> request = json.convertValue(frozen.get("request"), MAP);
            if (request.containsKey("secretSha256")) {
                if (secret == null || !MessageDigest.isEqual(fingerprint(String.valueOf(request.get("secretSalt")), secret).getBytes(StandardCharsets.US_ASCII),
                        String.valueOf(request.get("secretSha256")).getBytes(StandardCharsets.US_ASCII))) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret does not match the approved fingerprint");
                }
            } else if (secret != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            // A project credential may carry only its project id or only its code; look it up by what it was stored with.
            Map<?, ?> before = (Map<?, ?>) frozen.get("before");
            Long projectId = before.get("projectId") == null ? null : ((Number) before.get("projectId")).longValue();
            Object projectCode = before.get("projectCode");
            Map<String, Object> latest = snapshot(actor, change, id, projectId, projectCode == null ? null : String.valueOf(projectCode), request);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
            after = latest.get("after") == null ? null : json.convertValue(latest.get("after"), MAP);
            if (after != null && secret != null) after.put("secret", secret);
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try {
            response = change == Change.DISABLE ? credentials.disable(actor.session(), id) : credentials.update(actor.session(), id, after);
        } catch (ResponseStatusException definite) {
            if (!definite.getStatusCode().is4xxClientError()) return unknown(actor, approvalId);
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_WORKFLOW_CREDENTIAL_REJECTED");
            return ResponseEntity.status(definite.getStatusCode()).build();
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_WORKFLOW_CREDENTIAL_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknown(actor, approvalId);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Change change, long id, Long projectId, String projectCode, Map<String, Object> request) {
        if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Set<String> allowed = change == Change.UPDATE ? Set.of("name", "type", "scope", "projectId", "projectCode", "secretSalt", "secretSha256") : Set.of();
        if (!allowed.containsAll(request.keySet()) || change == Change.UPDATE && request.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        ResponseEntity<?> response = credentials.list(actor.session(), projectId, projectCode);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new ResponseStatusException(response == null ? HttpStatus.BAD_GATEWAY : response.getStatusCode());
        }
        JsonNode rows = json.valueToTree(response.getBody());
        JsonNode row = null;
        if (rows.isArray()) for (JsonNode item : rows) if (item.path("id").asLong() == id) row = item;
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String, Object> before = new LinkedHashMap<>();
        for (String field : FROZEN) before.put(field, json.convertValue(row.get(field), Object.class));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", change.name()); result.put("credentialId", id);
        result.put("request", request); result.put("before", before);
        if (change == Change.UPDATE) {
            // The owner replaces the whole credential, so fields the request leaves out keep their current values.
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("credentialRef", before.get("credentialRef"));
            for (String field : List.of("name", "type", "scope", "projectId", "projectCode")) {
                after.put(field, request.containsKey(field) ? request.get(field) : before.get(field));
            }
            after.put("status", before.get("status"));
            result.put("after", after);
            result.put("secretChanged", request.containsKey("secretSha256"));
        } else {
            result.put("after", null);
        }
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private String fingerprint(String salt, Map<String, Object> secret) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(json.writeValueAsBytes(sorted(secret))));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), sorted(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(McpPlatformWorkflowCredentialService::sorted).toList();
        return value;
    }

    private static String subject(long id) { return "WORKFLOW_CREDENTIAL:" + id; }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Workflow credential changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 400 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId);
        body.put("message", "凭据修改结果未确认，请查询原审批并重新读取凭据；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
