package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSkillAccessPolicyTest {

    private final AgentSkillAccessPolicy policy = new AgentSkillAccessPolicy();

    @Test
    void privateSkillIsVisibleOnlyToItsOwnerOrPlatformAdmin() {
        SkillSummary skill = skill("PRIVATE", 7L, null);

        assertTrue(policy.canAccess(session(7L, List.of(), "GLOBAL", "*"), "skill:read", skill));
        assertFalse(policy.canAccess(session(8L, List.of(), "GLOBAL", "*"), "skill:read", skill));
        assertTrue(policy.canAccess(session(8L, List.of("PLATFORM_ADMIN"), "GLOBAL", "*"),
                "skill:read", skill));
    }

    @Test
    void projectSkillRequiresMatchingGrantAndMatchingAgentProject() {
        SkillSummary skill = skill("PROJECT", 7L, "finance-core");
        PlatformAuthenticatedSession matching = session(8L, List.of(), "PROJECT", "finance-core");
        PlatformAuthenticatedSession other = session(8L, List.of(), "PROJECT", "hr-core");

        assertTrue(policy.canAccess(matching, "skill:read", skill));
        assertFalse(policy.canAccess(other, "skill:read", skill));
        assertDoesNotThrow(() -> policy.requireAgentProject(skill, "finance-core"));
        assertThrows(AgentSkillException.class, () -> policy.requireAgentProject(skill, "hr-core"));
        assertThrows(AgentSkillException.class, () -> policy.requireAgentProject(skill, null));
    }

    @Test
    void sharedAndPublicGovernanceRequiresGlobalGrantButProjectUsersMayReadAndBind() {
        SkillSummary shared = skill("SHARED", null, null);
        PlatformAuthenticatedSession projectReader = session(8L, List.of(), "PROJECT", "finance-core");
        PlatformAuthenticatedSession projectPublisher = sessionWithPermission(
                8L, "skill:publish", "PROJECT", "finance-core");
        PlatformAuthenticatedSession globalPublisher = sessionWithPermission(
                8L, "skill:publish", "GLOBAL", "*");

        assertTrue(policy.canAccess(projectReader, "skill:read", shared));
        assertTrue(policy.canAccess(projectReader, "skill:bind", shared));
        assertFalse(policy.canAccess(projectPublisher, "skill:publish", shared));
        assertTrue(policy.canAccess(globalPublisher, "skill:publish", shared));
        assertDoesNotThrow(() -> policy.requireAgentBindScope(
                projectReader, "skill:bind", "finance-core"));
        assertThrows(AgentSkillException.class, () -> policy.requireAgentBindScope(
                projectReader, "skill:bind", "hr-core"));
        assertThrows(AgentSkillException.class, () -> policy.requireAgentBindScope(
                projectReader, "skill:bind", null));
    }

    @Test
    void missingPermissionUsesTheStableSkillAccessDeniedContract() {
        PlatformAuthenticatedSession reader = sessionWithPermission(
                8L, "skill:read", "GLOBAL", "*");

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> policy.requirePermission(reader, "skill:import"));

        assertEquals("SKILL_ACCESS_DENIED", failure.code());
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, failure.status());
    }

    private PlatformAuthenticatedSession session(Long userId,
                                                 List<String> roles,
                                                 String scopeType,
                                                 String scopeValue) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(userId);
        user.setUsername("user-" + userId);
        return new PlatformAuthenticatedSession(
                user,
                "session",
                LocalDateTime.now().plusHours(1),
                roles,
                List.of("skill:read", "skill:import", "skill:bind"),
                List.of(
                        new PlatformPermissionGrant("skill:read", scopeType, scopeValue),
                        new PlatformPermissionGrant("skill:import", scopeType, scopeValue),
                        new PlatformPermissionGrant("skill:bind", scopeType, scopeValue)));
    }

    private PlatformAuthenticatedSession sessionWithPermission(Long userId,
                                                               String permission,
                                                               String scopeType,
                                                               String scopeValue) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(userId);
        user.setUsername("user-" + userId);
        return new PlatformAuthenticatedSession(
                user,
                "session",
                LocalDateTime.now().plusHours(1),
                List.of(),
                List.of(permission),
                List.of(new PlatformPermissionGrant(permission, scopeType, scopeValue)));
    }

    private SkillSummary skill(String visibility, Long ownerUserId, String projectCode) {
        return new SkillSummary(
                11L, "team", "demo-skill", "Demo", "Description", visibility,
                ownerUserId, projectCode, "ACTIVE", 21L, 21L, 1L, LocalDateTime.now(),
                "1.0.0", "PUBLISHED", "1.0.0");
    }
}
