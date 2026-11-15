package com.enterprise.ai.runtime.execution.interaction;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Named approvers of a HUMAN_APPROVAL node. When present, the person running the Workflow cannot
 * decide: one of the listed platform users, or anyone holding one of the listed platform roles,
 * decides from their 待办 list, and the run continues as the original runner.
 */
public record WorkflowApprovalAssignment(List<Approver> users, List<String> roles, boolean allowRunner) {

    /** Persisted interaction type for sessions decided by named approvers. */
    public static final String INTERACTION_TYPE = "ASSIGNED_APPROVAL";
    /** uiRequest component shown to the runner while the approvers decide. */
    public static final String COMPONENT = "approval_pending";
    public static final String METADATA_KEY = "approvalAssignment";
    public static final String DECISION_KEY = "approvalDecision";
    public static final String RUNNER_KEY = "approvalRunner";
    public static final int DEFAULT_TTL_SECONDS = 24 * 3600;
    public static final int MAX_TTL_SECONDS = 30 * 24 * 3600;
    static final int MAX_APPROVERS = 50;

    public record Approver(String username, String displayName) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("username", username);
            if (StringUtils.hasText(displayName)) map.put("displayName", displayName);
            return map;
        }

        String label() {
            return StringUtils.hasText(displayName) ? displayName : username;
        }
    }

    public WorkflowApprovalAssignment {
        users = users == null ? List.of() : List.copyOf(users);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /**
     * Reads {@code approverMode/approverUsers/approverRoles/allowRunnerApprove} from node config.
     * Returns null for the default "runner confirms" mode.
     */
    public static WorkflowApprovalAssignment fromNodeConfig(Map<String, Object> config) {
        if (config == null) return null;
        String mode = text(config.get("approverMode"));
        if (mode == null || !"ASSIGNED".equalsIgnoreCase(mode)) return null;
        return new WorkflowApprovalAssignment(approvers(config.get("approverUsers")),
                roleCodes(config.get("approverRoles")), Boolean.TRUE.equals(config.get("allowRunnerApprove")));
    }

    /** Restores an assignment that Runtime itself persisted on the interaction session. */
    public static WorkflowApprovalAssignment fromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        return new WorkflowApprovalAssignment(approvers(map.get("users")), roleCodes(map.get("roles")),
                Boolean.TRUE.equals(map.get("allowRunner")));
    }

    public boolean isEmpty() {
        return users.isEmpty() && roles.isEmpty();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("users", users.stream().map(Approver::toMap).toList());
        map.put("roles", roles);
        map.put("allowRunner", allowRunner);
        return map;
    }

    /** True when this platform user may decide; both values come from Control's signed session. */
    public boolean assignedTo(String username, Collection<String> platformRoles) {
        if (StringUtils.hasText(username)) {
            String normalized = username.trim();
            if (users.stream().anyMatch(user -> user.username().equalsIgnoreCase(normalized))) return true;
        }
        if (platformRoles == null) return false;
        for (String role : platformRoles) {
            if (role != null && roles.contains(role.trim().toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** Short Chinese label such as 「张经理、管理人员」 for the runner's waiting card. */
    public String assigneeLabel() {
        List<String> parts = new ArrayList<>();
        users.forEach(user -> parts.add(user.label()));
        roles.forEach(role -> parts.add(roleLabel(role)));
        return parts.isEmpty() ? "指定审批人" : String.join("、", parts);
    }

    public static String roleLabel(String role) {
        return switch (role == null ? "" : role) {
            case "PLATFORM_ADMIN" -> "平台管理员";
            case "MANAGER" -> "管理人员";
            case "DEVELOPER" -> "开发人员";
            case "OPERATOR" -> "运营人员";
            default -> role;
        };
    }

    /** Copies the approver's comment and identity from the resume values into the node output. */
    public static void copyDecisionFields(Object submitted, Map<String, Object> structured) {
        if (!(submitted instanceof Map<?, ?> values)) return;
        for (String key : List.of("comment", "approverUsername", "approverName", "decidedAt")) {
            if (values.get(key) != null) structured.put(key, values.get(key));
        }
    }

    /** Makes {@code approved/decision/comment} readable as {@code {{ alias.xxx }}} by later nodes. */
    public static void mergeOutput(Map<String, Object> context, String alias, Map<String, Object> structured) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (context.get(alias) instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }
        merged.putAll(structured);
        context.put(alias, merged);
    }

    private static List<Approver> approvers(Object raw) {
        if (!(raw instanceof Collection<?> values)) return List.of();
        Map<String, Approver> unique = new LinkedHashMap<>();
        for (Object value : values) {
            String username;
            String displayName = null;
            if (value instanceof Map<?, ?> map) {
                username = text(map.get("username"));
                displayName = text(map.get("displayName"));
            } else {
                username = text(value);
            }
            if (username == null || username.length() > 96 || unique.size() >= MAX_APPROVERS) continue;
            unique.putIfAbsent(username.toLowerCase(Locale.ROOT), new Approver(username,
                    displayName == null ? null : displayName.substring(0, Math.min(displayName.length(), 128))));
        }
        return List.copyOf(unique.values());
    }

    private static List<String> roleCodes(Object raw) {
        if (!(raw instanceof Collection<?> values)) return List.of();
        Set<String> codes = new LinkedHashSet<>();
        for (Object value : values) {
            String code = text(value);
            if (code != null && code.matches("[A-Za-z][A-Za-z0-9_]{1,63}") && codes.size() < MAX_APPROVERS) {
                codes.add(code.toUpperCase(Locale.ROOT));
            }
        }
        return List.copyOf(codes);
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
