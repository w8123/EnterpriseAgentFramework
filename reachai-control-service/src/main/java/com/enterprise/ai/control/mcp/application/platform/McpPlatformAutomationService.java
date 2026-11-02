package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAutomationPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAutomationPort.Operation;
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
 * An Automation runs its target unattended on a schedule under its creator's identity, so creating, editing, resuming
 * or archiving one goes to owner review and runs once. Pausing, running now, retrying and cancelling an occurrence act
 * on an Automation the owner already configured and are as direct as the console buttons; an unconfirmed result is
 * reported as UNKNOWN and never resent.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformAutomationService {
    public static final String STATE_CHANGED = "AUTOMATION_CHANGED";
    public enum Change { CREATE, UPDATE, RESUME, ARCHIVE }
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final Set<String> FIELDS = Set.of("name", "description", "projectId", "projectCode", "activate", "target",
            "schedule", "executionPolicy", "input");
    // Fire times move on their own as the schedule runs; they are not part of what the owner approves.
    private static final List<String> FROZEN_SUMMARY = List.of("automationKey", "name", "description", "tenantId", "projectId",
            "projectCode", "status", "revision", "targetType", "targetId", "targetVersionId", "triggerType", "scheduleLabel", "timeZone");
    private final McpPlatformAutomationPort automations;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> pause(ConnectionIdentity actor, String key, long expectedRevision) {
        return direct(actor, Operation.PAUSE, key, null, Map.of("expectedRevision", expectedRevision));
    }

    public ResponseEntity<?> operate(ConnectionIdentity actor, Operation operation, String key, Long occurrenceId, Map<String, Object> body) {
        if (!Set.of(Operation.RUN, Operation.RETRY, Operation.CANCEL).contains(operation)) throw new IllegalArgumentException("not a direct operation");
        return direct(actor, operation, key, occurrenceId, body);
    }

    private ResponseEntity<?> direct(ConnectionIdentity actor, Operation operation, String key, Long occurrenceId, Map<String, Object> body) {
        ResponseEntity<?> response;
        try { response = automations.call(actor.session(), operation, key, occurrenceId, Map.of(), body); }
        catch (ResponseStatusException | IllegalArgumentException definite) { throw definite; }
        catch (RuntimeException lost) { response = null; }
        if (response == null || response.getStatusCode().is5xxServerError()) {
            Map<String, Object> unknown = new LinkedHashMap<>();
            unknown.put("status", "UNKNOWN"); unknown.put("automationKey", key);
            unknown.put("message", "结果未确认，请先用 reachai_automation_get 或 reachai_automation_occurrences 查看，不要直接再次执行。");
            return ResponseEntity.accepted().body(unknown);
        }
        return response;
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, Change change, String key, Map<String, Object> fields, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, change, key, fields); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.AUTOMATION_CHANGE, subject(key), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.AUTOMATION_CHANGE);
        var frozen = claim.arguments();
        Change change;
        String key;
        Map<String, Object> after;
        Long revision;
        try {
            change = Change.valueOf(String.valueOf(frozen.get("operation")));
            key = (String) frozen.get("automationKey");
            if (!subject(key).equals(claim.subject())) throw changed();
            after = frozen.get("after") == null ? null : json.convertValue(frozen.get("after"), MAP);
            var latest = snapshot(actor, change, key, after);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
            revision = frozen.get("before") instanceof Map<?, ?> before && before.get("revision") instanceof Number number ? number.longValue() : null;
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (after != null) body.putAll(after);
        // Runtime refuses the write if the Automation moved past the revision the owner reviewed.
        if (revision != null) body.put("expectedRevision", revision);
        ResponseEntity<?> response;
        try {
            response = automations.call(actor.session(), switch (change) {
                case CREATE -> Operation.CREATE;
                case UPDATE -> Operation.UPDATE;
                case RESUME -> Operation.RESUME;
                case ARCHIVE -> Operation.ARCHIVE;
            }, key, null, Map.of(), body);
        } catch (ResponseStatusException rejected) {
            if (!rejected.getStatusCode().is4xxClientError()) return unknown(actor, approvalId, key);
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_AUTOMATION_REJECTED");
            return ResponseEntity.status(rejected.getStatusCode()).build();
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId, key);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_AUTOMATION_REJECTED");
            return response;
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknown(actor, approvalId, key);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Change change, String key, Map<String, Object> input) {
        if (change == Change.CREATE ? key != null : key == null || !key.matches("aut_[a-f0-9]{32}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", change.name()); result.put("automationKey", key);
        Map<String, Object> before = null;
        if (change != Change.CREATE) {
            var detail = automations.call(actor.session(), Operation.GET, key, null, Map.of(), null);
            if (detail == null || !detail.getStatusCode().is2xxSuccessful() || detail.getBody() == null) {
                throw new ResponseStatusException(detail == null ? HttpStatus.SERVICE_UNAVAILABLE : detail.getStatusCode());
            }
            JsonNode root = json.valueToTree(detail.getBody());
            JsonNode summary = root.path("automation");
            if (!key.equals(summary.path("automationKey").asText())) throw changed();
            requireWrite(actor.session(), summary.path("projectCode").isTextual() ? summary.path("projectCode").asText() : null);
            before = new LinkedHashMap<>();
            for (String field : FROZEN_SUMMARY) before.put(field, json.convertValue(summary.get(field), Object.class));
            before.put("currentVersion", json.convertValue(root.get("currentVersion"), Object.class));
            if (change == Change.ARCHIVE && "ARCHIVED".equals(before.get("status"))) throw changed();
        }
        result.put("before", before);
        if (change == Change.CREATE || change == Change.UPDATE) {
            if (input == null || input.isEmpty() || !FIELDS.containsAll(input.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            Object project = input.get("projectCode");
            if (project != null && !(project instanceof String)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            if (change == Change.CREATE || project != null) requireWrite(actor.session(), (String) project);
            result.put("after", input);
        } else {
            if (input != null && !input.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            result.put("after", null);
        }
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private static void requireWrite(PlatformAuthenticatedSession session, String projectCode) {
        boolean allowed = session.hasGlobalPermission(PlatformPermissions.AUTOMATION_WRITE)
                || projectCode != null && !projectCode.isBlank()
                && session.hasResourcePermission(PlatformPermissions.AUTOMATION_WRITE, "PROJECT", null, projectCode.trim());
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private static String subject(String key) { return "AUTOMATION:" + (key == null ? "NEW" : key); }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Automation changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 400 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, String key) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId); body.put("automationKey", key);
        body.put("message", "自动化修改结果未确认，请查询原审批并重新读取自动化列表；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
