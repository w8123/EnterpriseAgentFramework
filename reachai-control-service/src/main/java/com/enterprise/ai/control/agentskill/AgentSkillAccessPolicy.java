package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/** Enforces real owner/project visibility instead of treating scope as a display label. */
@Component
public class AgentSkillAccessPolicy {

    private static final Set<String> GLOBAL_GOVERNANCE_PERMISSIONS = Set.of(
            "skill:review", "skill:publish", "skill:script:approve");

    public void requirePermission(PlatformAuthenticatedSession session, String permission) {
        if (!hasAnyPermission(session, permission)) {
            throw AgentSkillException.forbidden(
                    "platform permission is required: " + permission);
        }
    }

    public boolean canAccess(PlatformAuthenticatedSession session,
                             String permission,
                             SkillSummary skill) {
        if (skill == null || !hasAnyPermission(session, permission)) return false;
        if (isPlatformAdmin(session)) return true;
        String visibility = normalizedVisibility(skill.visibility());
        return switch (visibility) {
            case "PRIVATE" -> sameUser(skill.ownerUserId(), session);
            case "PROJECT" -> StringUtils.hasText(skill.projectCode())
                    && session.hasResourcePermission(permission, "PROJECT", null, skill.projectCode());
            case "SHARED", "PUBLIC" -> !GLOBAL_GOVERNANCE_PERMISSIONS.contains(permission)
                    || session.hasGlobalPermission(permission);
            default -> false;
        };
    }

    public void requireAccess(PlatformAuthenticatedSession session,
                              String permission,
                              SkillSummary skill) {
        requirePermission(session, permission);
        if (!canAccess(session, permission, skill)) {
            // Do not reveal private package identities to callers outside the scope.
            throw AgentSkillException.notFound("Skill not found in the caller's accessible scope");
        }
    }

    public void requireImportScope(PlatformAuthenticatedSession session,
                                   String permission,
                                   String visibility,
                                   String projectCode) {
        requirePermission(session, permission);
        String normalized = normalizedVisibility(visibility);
        if ("PROJECT".equals(normalized)) {
            if (!StringUtils.hasText(projectCode)
                    || !session.hasResourcePermission(permission, "PROJECT", null, projectCode.trim())) {
                throw AgentSkillException.forbidden(
                        "Project-scoped Skill import requires permission for the target project");
            }
            return;
        }
        if (("SHARED".equals(normalized) || "PUBLIC".equals(normalized))
                && !session.hasGlobalPermission(permission)) {
            throw AgentSkillException.forbidden(
                    "Shared or public Skill import requires a global platform permission");
        }
    }

    public void requireAgentProject(SkillSummary skill, String agentProjectCode) {
        if (!canBindToAgentProject(skill, agentProjectCode)) {
            throw AgentSkillException.forbidden(
                    "Project-scoped Skill can only be bound to an Agent in the same project");
        }
    }

    /** A readable/public Skill never grants write access to an Agent in another project. */
    public void requireAgentBindScope(PlatformAuthenticatedSession session,
                                      String permission,
                                      String agentProjectCode) {
        requirePermission(session, permission);
        boolean allowed = StringUtils.hasText(agentProjectCode)
                ? session.hasResourcePermission(permission, "PROJECT", null, agentProjectCode.trim())
                : session.hasGlobalPermission(permission);
        if (!allowed) {
            throw AgentSkillException.forbidden(
                    "Binding a Skill requires permission for the target Agent project");
        }
    }

    public boolean canBindToAgentProject(SkillSummary skill, String agentProjectCode) {
        if (skill == null || !"PROJECT".equals(normalizedVisibility(skill.visibility()))) return true;
        return StringUtils.hasText(skill.projectCode())
                && StringUtils.hasText(agentProjectCode)
                && skill.projectCode().trim().equals(agentProjectCode.trim());
    }

    private boolean hasAnyPermission(PlatformAuthenticatedSession session, String permission) {
        return session != null && session.permissions() != null
                && (session.permissions().contains(permission) || session.permissions().contains("*"));
    }

    private boolean sameUser(Long ownerUserId, PlatformAuthenticatedSession session) {
        return ownerUserId != null && session != null && session.user() != null
                && ownerUserId.equals(session.user().getId());
    }

    private boolean isPlatformAdmin(PlatformAuthenticatedSession session) {
        return session != null && session.roles() != null
                && session.roles().stream().anyMatch("PLATFORM_ADMIN"::equalsIgnoreCase);
    }

    private String normalizedVisibility(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "PRIVATE";
    }
}
