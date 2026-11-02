package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformIdentityPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformIdentityPort.Operation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A runtime user mapping decides whose business identity a platform user acts as, and a business user identity decides
 * which business-system account and roles a person is recognised by. Changing either goes to owner review and runs once;
 * the AI can never map its owner's own platform account to a business identity.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformIdentityMappingService {
    public static final String STATE_CHANGED = "IDENTITY_MAPPING_CHANGED";
    public static final String SELF_CHANGE = "IDENTITY_MAPPING_SELF_CHANGE_FORBIDDEN";
    public enum Change { MAPPING_CREATE, MAPPING_DELETE, BUSINESS_IDENTITY_SAVE }
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<LinkedHashMap<String, Object>>> LIST = new TypeReference<>() { };
    private static final Set<String> MAPPING_FIELDS = Set.of("tenantId", "platformUserId", "runtimeUserId", "globalUserId", "externalUserId",
            "projectId", "projectCode");
    private static final Set<String> IDENTITY_FIELDS = Set.of("id", "appId", "externalUserId", "externalUserName", "deptId", "deptName",
            "status", "roles");
    private final McpPlatformIdentityPort identities;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> read(ConnectionIdentity actor, Operation operation, Long id, Map<String, Object> query) {
        if (!Set.of(Operation.RUNTIME_MAPPINGS, Operation.BUSINESS_USERS, Operation.BUSINESS_USER_IDENTITIES).contains(operation)) {
            throw new IllegalArgumentException("not a read");
        }
        return identities.call(actor.session(), operation, id, query, null);
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, Change change, Long id, Map<String, Object> fields, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, change, id, fields); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.IDENTITY_MAPPING_CHANGE, subject(change, id, frozen), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.IDENTITY_MAPPING_CHANGE);
        var frozen = claim.arguments();
        Change change;
        Long id;
        Map<String, Object> request;
        try {
            change = Change.valueOf(String.valueOf(frozen.get("operation")));
            id = frozen.get("targetId") instanceof Number number ? number.longValue() : null;
            request = frozen.get("request") == null ? Map.of() : json.convertValue(frozen.get("request"), MAP);
            var latest = snapshot(actor, change, id, request);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!subject(change, id, latest).equals(claim.subject()) || !json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try {
            response = switch (change) {
                case MAPPING_CREATE -> identities.call(actor.session(), Operation.MAPPING_CREATE, null, Map.of(), request);
                case MAPPING_DELETE -> identities.call(actor.session(), Operation.MAPPING_DELETE, id, Map.of(), null);
                case BUSINESS_IDENTITY_SAVE -> identities.call(actor.session(), Operation.BUSINESS_IDENTITY_SAVE, id, Map.of(), request);
            };
        } catch (ResponseStatusException | IllegalArgumentException definite) {
            if (definite instanceof ResponseStatusException status && !status.getStatusCode().is4xxClientError()) {
                return unknown(actor, approvalId, change, id);
            }
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_IDENTITY_MAPPING_REJECTED");
            return ResponseEntity.status(definite instanceof ResponseStatusException status ? status.getStatusCode().value() : 400).build();
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId, change, id);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_IDENTITY_MAPPING_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknown(actor, approvalId, change, id);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Change change, Long id, Map<String, Object> input) {
        Map<String, Object> request = input == null ? Map.of() : input;
        Long self = actor.session().user() == null ? null : actor.session().user().getId();
        if (self == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", change.name()); result.put("targetId", id);
        result.put("request", request.isEmpty() ? null : request);
        switch (change) {
            case MAPPING_CREATE -> {
                if (id != null || !MAPPING_FIELDS.containsAll(request.keySet()) || !(request.get("tenantId") instanceof String tenant)
                        || !(request.get("platformUserId") instanceof Number platformUser)) throw badRequest();
                // Mapping the owner's own account would let the AI choose whose business identity the owner acts as.
                if (platformUser.longValue() == self) throw new ResponseStatusException(HttpStatus.FORBIDDEN, SELF_CHANGE);
                result.put("before", activeMappings(actor, tenant, platformUser.longValue()));
                result.put("after", request);
            }
            case MAPPING_DELETE -> {
                if (id == null || id <= 0 || !Set.of("tenantId").equals(request.keySet()) || !(request.get("tenantId") instanceof String tenant)) {
                    throw badRequest();
                }
                var mapping = list(actor, Operation.RUNTIME_MAPPINGS, null, Map.of("tenantId", tenant, "limit", 500)).stream()
                        .filter(row -> row.get("id") instanceof Number number && number.longValue() == id).findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
                if (!"ACTIVE".equals(mapping.get("status"))) throw changed();
                result.put("before", mapping);
                result.put("after", null);
            }
            case BUSINESS_IDENTITY_SAVE -> {
                if (id == null || id <= 0 || request.isEmpty() || !IDENTITY_FIELDS.containsAll(request.keySet())
                        || !(request.get("appId") instanceof String) || !(request.get("externalUserId") instanceof String)) throw badRequest();
                List<Map<String, Object>> bound = new ArrayList<>();
                for (var identity : list(actor, Operation.BUSINESS_USER_IDENTITIES, id, Map.of())) {
                    identity.remove("lastSeenAt");
                    bound.add(identity);
                }
                result.put("before", bound);
                result.put("after", request);
            }
        }
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private List<Map<String, Object>> activeMappings(ConnectionIdentity actor, String tenantId, long platformUserId) {
        return list(actor, Operation.RUNTIME_MAPPINGS, null, Map.of("tenantId", tenantId, "platformUserId", platformUserId, "status", "ACTIVE"));
    }

    private List<Map<String, Object>> list(ConnectionIdentity actor, Operation operation, Long id, Map<String, Object> query) {
        var response = identities.call(actor.session(), operation, id, query, null);
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
        }
        JsonNode body = json.valueToTree(response.getBody());
        return new ArrayList<>(json.convertValue(body.isArray() ? body : body.path("records"), LIST));
    }

    private static String subject(Change change, Long id, Map<String, Object> frozen) {
        return switch (change) {
            case MAPPING_CREATE -> "RUNTIME_USER_MAPPING:NEW:" + ((Map<?, ?>) frozen.get("after")).get("platformUserId");
            case MAPPING_DELETE -> "RUNTIME_USER_MAPPING:" + id;
            case BUSINESS_IDENTITY_SAVE -> "BUSINESS_USER:" + id;
        };
    }

    private static ResponseStatusException badRequest() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Identity mapping changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 400 : 503;
        if (status == 403 && failure instanceof ResponseStatusException denied && SELF_CHANGE.equals(denied.getReason())) {
            return ResponseEntity.status(403).body(Map.of("code", SELF_CHANGE));
        }
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, Change change, Long id) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId); body.put("operation", change.name()); body.put("targetId", id);
        body.put("message", "身份映射修改结果未确认，请查询原审批并重新读取映射或业务用户身份；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
