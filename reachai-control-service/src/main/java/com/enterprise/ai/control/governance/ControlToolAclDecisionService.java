package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tool ACL decision core shared by the explain endpoint and MCP tools/call.
 *
 * <p>Semantics (aligned with {@code control_tool_acl}): DENY wins, no matching
 * rule defaults to deny. The MCP protocol surface intentionally enforces the
 * strict variant — an empty role list also denies, it never silently warns.</p>
 */
@Service
@RequiredArgsConstructor
public class ControlToolAclDecisionService {

    public static final String DECISION_ALLOW = "ALLOW";
    public static final String DECISION_DENY_EXPLICIT = "DENY_EXPLICIT";
    public static final String DECISION_DENY_NO_MATCH = "DENY_NO_MATCH";
    public static final String DECISION_SKIPPED = "SKIPPED";

    private final ControlToolAclMapper mapper;

    public boolean isAllowed(List<String> roles, String targetKind, String targetName) {
        return DECISION_ALLOW.equals(decide(roles, targetKind, targetName));
    }

    /**
     * Resolves the ACL decision for one target. Roles are taken from verified
     * platform state (session roles, MCP Client roles) — never from request bodies.
     */
    public String decide(List<String> roles, String targetKind, String targetName) {
        return decide(roles, null, null, targetKind, targetName);
    }

    /** Project-aware decision used by externally issued credentials. Global rules still match every project. */
    public String decide(List<String> roles, Long projectId, String projectCode,
                         String targetKind, String targetName) {
        List<String> normalizedRoles = normalizeRoles(roles);
        if (normalizedRoles.isEmpty()) {
            return DECISION_SKIPPED;
        }
        return decide(normalizedRoles, projectId, projectCode, normalizeKind(targetKind),
                requireText(targetName, "targetName"), enabledRules(normalizedRoles));
    }

    /** Resolves multiple targets with one rule read so management explain and enforcement cannot drift. */
    public Map<String, String> decideAll(List<String> roles, Long projectId, String projectCode,
                                         List<Target> targets) {
        List<String> normalizedRoles = normalizeRoles(roles);
        List<ControlToolAclEntity> rules = normalizedRoles.isEmpty() ? List.of() : enabledRules(normalizedRoles);
        Map<String, String> decisions = new LinkedHashMap<>();
        for (Target target : targets == null ? List.<Target>of() : targets) {
            String kind = normalizeKind(target == null ? null : target.kind());
            String name = requireText(target == null ? null : target.name(), "target.name");
            decisions.put(name, normalizedRoles.isEmpty()
                    ? DECISION_SKIPPED
                    : decide(normalizedRoles, projectId, projectCode, kind, name, rules));
        }
        return decisions;
    }

    private String decide(List<String> normalizedRoles, Long projectId, String projectCode,
                          String kind, String name, List<ControlToolAclEntity> rules) {
        boolean allowed = false;
        for (ControlToolAclEntity rule : rules) {
            if (!normalizedRoles.contains(rule.getRoleCode())
                    || !matchesProject(rule, projectId, projectCode)
                    || !matches(rule, kind, name)) {
                continue;
            }
            if ("DENY".equalsIgnoreCase(rule.getPermission())) {
                return DECISION_DENY_EXPLICIT;
            }
            if ("ALLOW".equalsIgnoreCase(rule.getPermission())) {
                allowed = true;
            }
        }
        return allowed ? DECISION_ALLOW : DECISION_DENY_NO_MATCH;
    }

    private List<ControlToolAclEntity> enabledRules(List<String> normalizedRoles) {
        return mapper.selectList(new LambdaQueryWrapper<ControlToolAclEntity>()
                .in(ControlToolAclEntity::getRoleCode, normalizedRoles)
                .eq(ControlToolAclEntity::getEnabled, true));
    }

    private List<String> normalizeRoles(List<String> roles) {
        return roles == null ? List.of() : roles.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    /** Role codes are an ACL-managed catalog; MCP credential APIs must not invent new roles. */
    public Set<String> knownRoleCodes() {
        return mapper.selectList(new LambdaQueryWrapper<ControlToolAclEntity>()
                        .eq(ControlToolAclEntity::getEnabled, true))
                .stream()
                .map(ControlToolAclEntity::getRoleCode)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean matchesProject(ControlToolAclEntity rule, Long projectId, String projectCode) {
        if (rule.getProjectId() != null && !Objects.equals(rule.getProjectId(), projectId)) {
            return false;
        }
        if (StringUtils.hasText(rule.getProjectCode())
                && !Objects.equals(rule.getProjectCode().trim(), trim(projectCode))) {
            return false;
        }
        return true;
    }

    private boolean matches(ControlToolAclEntity rule, String targetKind, String targetName) {
        String ruleKind = upper(rule.getTargetKind());
        boolean kindMatches = Objects.equals(ruleKind, targetKind) || Objects.equals(ruleKind, "ALL");
        boolean nameMatches = Objects.equals(rule.getTargetName(), "*")
                || Objects.equals(rule.getTargetName(), targetName);
        return kindMatches && nameMatches;
    }

    private String normalizeKind(String value) {
        return StringUtils.hasText(value) ? upper(value) : "TOOL";
    }

    private String upper(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record Target(String kind, String name) {
    }
}
