package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAccountPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAccountPort.Operation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Accounts and roles decide who can do anything on the platform, so every change goes to owner review and runs once,
 * and the AI can never change its owner's own account, grants or a role its owner holds. Creating an account and
 * resetting a password stay with a person, because either would hand the AI a working login credential.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformAccountService {
    public static final String STATE_CHANGED = "PLATFORM_ACCOUNT_CHANGED";
    public static final String SELF_CHANGE = "PLATFORM_ACCOUNT_SELF_CHANGE_FORBIDDEN";
    public enum Change { UPDATE_USER, REVOKE_SESSIONS, REPLACE_GRANTS, CREATE_ROLE, UPDATE_ROLE }
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<LinkedHashMap<String, Object>>> LIST = new TypeReference<>() { };
    private static final List<String> USER_FIELDS = List.of("displayName", "email", "mobile", "status");
    private static final List<String> ROLE_FIELDS = List.of("roleName", "description", "status", "permissionIds");
    private static final Set<String> GRANT_FIELDS = Set.of("roleId", "scopeType", "scopeValue");
    // Login time and live session counts move on their own; they are not part of what the owner approves.
    private static final Set<String> VOLATILE = Set.of("lastLoginAt", "activeSessionCount", "userCount", "grantCount");
    private final McpPlatformAccountPort accounts;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> users(ConnectionIdentity actor, String keyword, int limit) {
        requireAdmin(actor);
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        var users = list(actor, Operation.USERS).stream()
                .filter(user -> needle.isEmpty() || USER_FIELDS.stream().map(user::get).anyMatch(value -> contains(value, needle))
                        || contains(user.get("username"), needle))
                .limit(limit).toList();
        return ResponseEntity.ok(Map.of("items", users, "count", users.size()));
    }

    public ResponseEntity<?> read(ConnectionIdentity actor, Operation operation, Map<String, Object> query) {
        if (!Set.of(Operation.ROLES, Operation.PERMISSIONS, Operation.AUDIT_EVENTS).contains(operation)) throw new IllegalArgumentException("not a read");
        requireAdmin(actor);
        return accounts.call(actor.session(), operation, null, query, null);
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, Change change, Long id, Map<String, Object> input, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, change, id, input); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.ACCOUNT_CHANGE, subject(change, id), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.ACCOUNT_CHANGE);
        var frozen = claim.arguments();
        Change change;
        Long id;
        Map<String, Object> after;
        try {
            change = Change.valueOf(String.valueOf(frozen.get("operation")));
            id = frozen.get("targetId") instanceof Number number ? number.longValue() : null;
            if (!subject(change, id).equals(claim.subject())) throw changed();
            Map<String, Object> request = frozen.get("request") == null ? null : json.convertValue(frozen.get("request"), MAP);
            var latest = snapshot(actor, change, id, request);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
            after = latest.get("after") == null ? null : json.convertValue(latest.get("after"), MAP);
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        Object body = switch (change) {
            case REVOKE_SESSIONS -> null;
            case REPLACE_GRANTS -> grantCommands(after);
            case CREATE_ROLE, UPDATE_ROLE -> withoutLabels(after);
            case UPDATE_USER -> after;
        };
        ResponseEntity<?> response;
        try {
            response = accounts.call(actor.session(), Operation.valueOf(change.name()), id, Map.of(), body);
        } catch (ResponseStatusException rejected) {
            if (!rejected.getStatusCode().is4xxClientError()) return unknown(actor, approvalId, change, id);
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_ACCOUNT_REJECTED");
            return ResponseEntity.status(rejected.getStatusCode()).build();
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId, change, id);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_ACCOUNT_REJECTED");
            return response;
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknown(actor, approvalId, change, id);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Change change, Long id, Map<String, Object> input) {
        requireAdmin(actor);
        Long self = actor.session().user() == null ? null : actor.session().user().getId();
        if (self == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (change == Change.CREATE_ROLE ? id != null : id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> request = input == null ? Map.of() : input;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", change.name()); result.put("targetId", id);
        result.put("request", request.isEmpty() ? null : request);
        var users = list(actor, Operation.USERS);
        var roles = list(actor, Operation.ROLES);
        Map<String, Object> before = null;
        Map<String, Object> after = null;
        switch (change) {
            case UPDATE_USER, REVOKE_SESSIONS, REPLACE_GRANTS -> {
                // The AI must never widen, keep alive or reshape the account it is acting for.
                if (id.equals(self)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, SELF_CHANGE);
                before = stable(find(users, id));
                if (change == Change.UPDATE_USER) {
                    after = merged(before, request, USER_FIELDS);
                } else if (change == Change.REPLACE_GRANTS) {
                    if (!Set.of("grants").equals(request.keySet()) || !(request.get("grants") instanceof List<?> grants)) throw badRequest();
                    after = Map.of("grants", grantView(grants, roles));
                } else if (!request.isEmpty()) {
                    throw badRequest();
                }
            }
            case CREATE_ROLE -> {
                if (!Set.of("roleCode", "roleName", "description", "status", "permissionIds").containsAll(request.keySet())
                        || !(request.get("roleCode") instanceof String) || !(request.get("roleName") instanceof String)) throw badRequest();
                after = new LinkedHashMap<>(request);
                after.put("permissionCodes", permissionCodes(actor, request.get("permissionIds")));
            }
            case UPDATE_ROLE -> {
                Map<String, Object> role = find(roles, id);
                boolean held = users.stream().filter(user -> self.equals(number(user.get("id"))))
                        .flatMap(user -> user.get("grants") instanceof List<?> grants ? grants.stream() : java.util.stream.Stream.empty())
                        .anyMatch(grant -> grant instanceof Map<?, ?> map && id.equals(number(map.get("roleId"))));
                if (held) throw new ResponseStatusException(HttpStatus.FORBIDDEN, SELF_CHANGE);
                before = stable(role);
                Map<String, Object> editable = new LinkedHashMap<>(request);
                Object acknowledge = editable.remove("acknowledgeAssignedUsers");
                if (acknowledge != null && !(acknowledge instanceof Boolean)) throw badRequest();
                after = merged(before, editable, ROLE_FIELDS);
                if (acknowledge != null) after.put("acknowledgeAssignedUsers", acknowledge);
                after.put("permissionCodes", permissionCodes(actor, after.get("permissionIds")));
            }
        }
        result.put("before", before);
        result.put("after", after);
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private static Map<String, Object> merged(Map<String, Object> before, Map<String, Object> input, List<String> fields) {
        if (input.isEmpty() || !fields.containsAll(input.keySet())) throw badRequest();
        Map<String, Object> after = new LinkedHashMap<>();
        for (String field : fields) after.put(field, input.containsKey(field) ? input.get(field) : before.get(field));
        boolean unchanged = fields.stream().allMatch(field -> Objects.equals(String.valueOf(after.get(field)), String.valueOf(before.get(field))));
        if (unchanged) throw badRequest();
        return after;
    }

    private List<Map<String, Object>> grantView(List<?> grants, List<Map<String, Object>> roles) {
        if (grants.size() > 50) throw badRequest();
        List<Map<String, Object>> view = new ArrayList<>();
        for (Object item : grants) {
            if (!(item instanceof Map<?, ?> grant) || !GRANT_FIELDS.containsAll(grant.keySet())) throw badRequest();
            Long roleId = number(grant.get("roleId"));
            if (roleId == null) throw badRequest();
            var role = roles.stream().filter(candidate -> roleId.equals(number(candidate.get("id")))).findFirst().orElseThrow(McpPlatformAccountService::badRequest);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("roleId", roleId); entry.put("roleCode", role.get("roleCode")); entry.put("roleName", role.get("roleName"));
            entry.put("scopeType", grant.get("scopeType")); entry.put("scopeValue", grant.get("scopeValue"));
            view.add(entry);
        }
        return view;
    }

    private List<String> permissionCodes(ConnectionIdentity actor, Object ids) {
        if (ids == null) return List.of();
        if (!(ids instanceof List<?> requested)) throw badRequest();
        var permissions = list(actor, Operation.PERMISSIONS);
        List<String> codes = new ArrayList<>();
        for (Object item : requested) {
            Long permissionId = number(item);
            codes.add(permissions.stream().filter(permission -> Objects.equals(permissionId, number(permission.get("id"))))
                    .map(permission -> String.valueOf(permission.get("permissionCode"))).findFirst()
                    .orElseThrow(McpPlatformAccountService::badRequest));
        }
        return codes;
    }

    private static List<Map<String, Object>> grantCommands(Map<String, Object> after) {
        List<Map<String, Object>> commands = new ArrayList<>();
        for (Object item : (List<?>) after.get("grants")) {
            Map<?, ?> grant = (Map<?, ?>) item;
            Map<String, Object> command = new LinkedHashMap<>();
            for (String field : List.of("roleId", "scopeType", "scopeValue")) command.put(field, grant.get(field));
            commands.add(command);
        }
        return commands;
    }

    private static Map<String, Object> withoutLabels(Map<String, Object> after) {
        Map<String, Object> body = new LinkedHashMap<>(after);
        body.remove("permissionCodes");
        return body;
    }

    private List<Map<String, Object>> list(ConnectionIdentity actor, Operation operation) {
        var response = accounts.call(actor.session(), operation, null, Map.of(), null);
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
        }
        return new ArrayList<>(json.convertValue(response.getBody(), LIST));
    }

    private static Map<String, Object> find(List<Map<String, Object>> items, Long id) {
        return items.stream().filter(item -> id.equals(number(item.get("id")))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static Map<String, Object> stable(Map<String, Object> view) {
        Map<String, Object> copy = new LinkedHashMap<>(view);
        VOLATILE.forEach(copy::remove);
        return copy;
    }

    private static void requireAdmin(ConnectionIdentity actor) {
        if (!actor.session().hasGlobalPermission(PlatformPermissions.PLATFORM_ADMIN)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private static Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && text.matches("\\d{1,18}")) return Long.valueOf(text);
        return null;
    }

    private static boolean contains(Object value, String needle) {
        return value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String subject(Change change, Long id) {
        return switch (change) {
            case CREATE_ROLE -> "PLATFORM_ROLE:NEW";
            case UPDATE_ROLE -> "PLATFORM_ROLE:" + id;
            default -> "PLATFORM_USER:" + id;
        };
    }

    private static ResponseStatusException badRequest() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Account changed");
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
        body.put("message", "账号或角色修改结果未确认，请查询原审批并重新读取账号、角色列表；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
