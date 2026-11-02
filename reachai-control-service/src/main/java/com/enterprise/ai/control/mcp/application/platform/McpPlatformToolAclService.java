package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.governance.ControlToolAclPlatformOperations;
import com.enterprise.ai.control.governance.ControlToolAclPlatformOperations.Operation;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tool call permission rules decide which roles' Agents may call which tools, so an AI change is an owner-reviewed
 * grant: the request freezes the stored rule (with its update time) and the exact rule to write, and runs once.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformToolAclService {
    public static final String STATE_CHANGED = "TOOL_ACL_CHANGED";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final String BATCH_SUBJECT = "TOOL_ACL:BATCH";
    private static final Set<String> BATCH_FIELDS = Set.of("roleCode", "projectId", "projectCode", "permission", "note", "targets");
    private static final List<String> FIELDS = List.of("roleCode", "projectId", "projectCode", "targetKind", "targetName", "permission", "note", "enabled");
    private final ControlToolAclPlatformOperations acl;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> request(ConnectionIdentity actor, Operation operation, Long id, Map<String, Object> rule, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, operation, id, rule); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.TOOL_ACL_CHANGE, subject(id), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.TOOL_ACL_CHANGE);
        var frozen = claim.arguments();
        if ("BATCH_GRANT".equals(frozen.get("operation"))) {
            if (!BATCH_SUBJECT.equals(claim.subject())) {
                approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
                return refusal(changed());
            }
            return executeBatch(actor, approvalId, frozen);
        }
        Operation operation;
        Long id;
        Map<String, Object> after;
        try {
            operation = Operation.valueOf(String.valueOf(frozen.get("operation")));
            id = frozen.get("aclId") == null ? null : ((Number) frozen.get("aclId")).longValue();
            if (!subject(id).equals(claim.subject())) throw changed();
            after = frozen.get("after") == null ? null : json.convertValue(frozen.get("after"), MAP);
            var latest = snapshot(actor, operation, id, after);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try { response = acl.change(actor.session(), operation, id, after == null ? Map.of() : after); }
        catch (RuntimeException failure) {
            var denied = refusal(failure);
            if (denied.getStatusCode().is4xxClientError()) {
                approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_TOOL_ACL_REJECTED");
                return denied;
            }
            return unknown(actor, approvalId, id);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_TOOL_ACL_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (!confirmed(response, operation, id, after)) return unknown(actor, approvalId, id);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    /**
     * A batch grant writes one role's permission on up to 100 tools at once and overwrites matching rules, so the
     * owner reviews the exact targets together with the rules that would be overwritten.
     */
    public ResponseEntity<?> requestBatch(ConnectionIdentity actor, Map<String, Object> command, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = batchSnapshot(actor, command); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.TOOL_ACL_CHANGE, BATCH_SUBJECT, frozen));
    }

    private ResponseEntity<?> executeBatch(ConnectionIdentity actor, String approvalId, Map<String, Object> frozen) {
        Map<String, Object> command;
        try {
            command = json.convertValue(frozen.get("after"), MAP);
            var latest = batchSnapshot(actor, command);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try { response = acl.grantBatch(actor.session(), command); }
        catch (RuntimeException failure) {
            var denied = refusal(failure);
            if (denied.getStatusCode().is4xxClientError()) {
                approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_TOOL_ACL_REJECTED");
                return denied;
            }
            return unknown(actor, approvalId, null);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_TOOL_ACL_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        JsonNode result = response == null || !response.getStatusCode().is2xxSuccessful() ? null : json.valueToTree(response.getBody());
        if (result == null || !result.path("ok").asBoolean(false) || result.path("count").asInt(-1) != ((List<?>) command.get("targets")).size()) {
            return unknown(actor, approvalId, null);
        }
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> batchSnapshot(ConnectionIdentity actor, Map<String, Object> command) {
        if (command == null || !BATCH_FIELDS.containsAll(command.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("roleCode", required(command.get("roleCode"), 64));
        Object projectId = command.get("projectId");
        if (projectId != null && !(projectId instanceof Number number && number.longValue() > 0)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        after.put("projectId", projectId == null ? null : ((Number) projectId).longValue());
        after.put("projectCode", optional(command.get("projectCode"), 64));
        String permission = command.get("permission") == null ? "ALLOW" : required(command.get("permission"), 16).toUpperCase(Locale.ROOT);
        if (!Set.of("ALLOW", "DENY").contains(permission)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        after.put("permission", permission);
        after.put("note", optional(command.get("note"), 500));
        if (!(command.get("targets") instanceof List<?> targets) || targets.isEmpty() || targets.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        List<Map<String, String>> normalized = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Object item : targets) {
            if (!(item instanceof Map<?, ?> target) || !Set.of("kind", "name").containsAll(target.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            String kind = target.get("kind") == null ? "TOOL" : required(target.get("kind"), 16).toUpperCase(Locale.ROOT);
            if (!Set.of("TOOL", "ALL").contains(kind)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            String name = required(target.get("name"), 256);
            if (!seen.add(kind + "\n" + name)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            normalized.add(Map.of("kind", kind, "name", name));
        }
        after.put("targets", normalized);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", "BATCH_GRANT"); result.put("aclId", null);
        result.put("overwritten", acl.batchExisting(actor.session(), (String) after.get("roleCode"), (Long) after.get("projectId"),
                (String) after.get("projectCode"), normalized));
        result.put("after", after);
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Operation operation, Long id, Map<String, Object> rule) {
        if (operation == Operation.CREATE ? id != null : id == null || id < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", operation.name()); result.put("aclId", id);
        Map<String, Object> before = null;
        if (operation != Operation.CREATE) {
            var response = acl.find(actor.session(), id);
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
            }
            JsonNode stored = json.valueToTree(response.getBody());
            if (stored == null || stored.path("id").asLong() != id) throw changed();
            before = new LinkedHashMap<>();
            for (String field : FIELDS) before.put(field, json.convertValue(stored.get(field), Object.class));
            before.put("updatedAt", stored.path("updatedAt").isNull() ? null : stored.path("updatedAt").asText());
        }
        result.put("before", before);
        result.put("after", switch (operation) {
            case CREATE, UPDATE -> normalized(rule);
            case TOGGLE -> {
                if (rule == null || !(rule.get("enabled") instanceof Boolean enabled) || rule.size() != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                if (enabled.equals(before.get("enabled"))) throw changed();
                yield Map.of("enabled", enabled);
            }
            case DELETE -> {
                if (rule != null && !rule.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                yield null;
            }
        });
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private Map<String, Object> normalized(Map<String, Object> rule) {
        if (rule == null || !Set.copyOf(FIELDS).containsAll(rule.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roleCode", required(rule.get("roleCode"), 64));
        Object projectId = rule.get("projectId");
        if (projectId != null && !(projectId instanceof Number number && number.longValue() > 0)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        result.put("projectId", projectId == null ? null : ((Number) projectId).longValue());
        result.put("projectCode", optional(rule.get("projectCode"), 64));
        String kind = rule.get("targetKind") == null ? "TOOL" : required(rule.get("targetKind"), 16).toUpperCase(Locale.ROOT);
        if (!Set.of("TOOL", "ALL").contains(kind)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        result.put("targetKind", kind);
        result.put("targetName", required(rule.get("targetName"), 256));
        String permission = rule.get("permission") == null ? "ALLOW" : required(rule.get("permission"), 16).toUpperCase(Locale.ROOT);
        if (!Set.of("ALLOW", "DENY").contains(permission)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        result.put("permission", permission);
        result.put("note", optional(rule.get("note"), 500));
        Object enabled = rule.get("enabled");
        if (enabled != null && !(enabled instanceof Boolean)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        result.put("enabled", enabled == null || (Boolean) enabled);
        return result;
    }

    private boolean confirmed(ResponseEntity<?> response, Operation operation, Long id, Map<String, Object> after) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return false;
        JsonNode result = json.valueToTree(response.getBody());
        if (result == null) return false;
        if (operation == Operation.DELETE) return result.path("ok").asBoolean(false);
        if (!result.path("id").canConvertToLong() || result.path("id").asLong() < 1 || operation != Operation.CREATE && result.path("id").asLong() != id) return false;
        for (var field : after.entrySet()) {
            JsonNode expected = json.valueToTree(field.getValue()), actual = result.path(field.getKey());
            if (expected.isNull() ? !(actual.isNull() || actual.isMissingNode()) : !expected.asText().equals(actual.asText())) return false;
        }
        return true;
    }

    private static String required(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.trim().length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return text.trim();
    }

    private static String optional(Object value, int max) {
        if (value == null || value instanceof String text && text.isBlank()) return null;
        return required(value, max);
    }

    private static String subject(Long id) { return "TOOL_ACL:" + (id == null ? "NEW" : id); }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Tool permission rule changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 409 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, Long id) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        var body = new LinkedHashMap<String, Object>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId); body.put("aclId", id);
        body.put("message", "调用权限规则的修改结果未确认，请查询原审批并重新读取规则列表；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
