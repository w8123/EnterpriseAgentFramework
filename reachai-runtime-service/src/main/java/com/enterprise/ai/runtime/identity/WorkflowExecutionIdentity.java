package com.enterprise.ai.runtime.identity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Trusted execution identity for Workflow credential scope and Knowledge ACL.
 * Must never be derived from GraphSpec business maps, model tool args, or public request body fields.
 * External JSON cannot set projectTrusted/userTrusted — only controlled factories may create trusted users.
 */
public final class WorkflowExecutionIdentity {

    public enum Source {
        AGENT,
        EMBED_SESSION,
        A2A_REMOTE_AGENT,
        AUTOMATION,
        MCP_REMOTE_CLIENT,
        STUDIO_PROJECT_TEST,
        DEBUG_UNTRUSTED
    }

    private final Source source;
    private final String tenantId;
    private final Long projectId;
    private final String projectCode;
    private final String userId;
    private final boolean projectTrusted;
    private final boolean userTrusted;

    private WorkflowExecutionIdentity(
            Source source,
            String tenantId,
            Long projectId,
            String projectCode,
            String userId,
            boolean projectTrusted,
            boolean userTrusted) {
        this.source = source == null ? Source.DEBUG_UNTRUSTED : source;
        this.tenantId = StringUtils.hasText(tenantId) ? tenantId.trim() : null;
        this.projectId = projectId;
        this.projectCode = StringUtils.hasText(projectCode) ? projectCode.trim() : null;
        this.userId = StringUtils.hasText(userId) ? userId.trim() : null;
        this.projectTrusted = projectTrusted;
        this.userTrusted = userTrusted;
    }

    public static WorkflowExecutionIdentity fromAgent(Long projectId, String projectCode) {
        return fromAgent(null, projectId, projectCode, null);
    }

    public static WorkflowExecutionIdentity fromAgent(Long projectId, String projectCode, String trustedUserId) {
        return fromAgent(null, projectId, projectCode, trustedUserId);
    }

    public static WorkflowExecutionIdentity fromAgent(String tenantId,
                                                      Long projectId,
                                                      String projectCode,
                                                      String trustedUserId) {
        boolean userTrusted = StringUtils.hasText(trustedUserId);
        return new WorkflowExecutionIdentity(
                Source.AGENT, tenantId, projectId, projectCode, trustedUserId, true, userTrusted);
    }

    public static WorkflowExecutionIdentity fromEmbedSession(Long projectId, String projectCode, String userId) {
        return fromEmbedSession(null, projectId, projectCode, userId);
    }

    public static WorkflowExecutionIdentity fromEmbedSession(String tenantId,
                                                             Long projectId,
                                                             String projectCode,
                                                             String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("Embed session identity requires userId");
        }
        return new WorkflowExecutionIdentity(
                Source.EMBED_SESSION, tenantId, projectId, projectCode, userId, true, true);
    }

    /**
     * A remote A2A Principal may execute the published Agent/project contract,
     * but it is never promoted to a ReachAI business user for personal-memory
     * or user-ACL resolution.
     */
    public static WorkflowExecutionIdentity fromA2aRemoteAgent(
            String tenantId,
            Long projectId,
            String projectCode,
            String principalKey) {
        if (!StringUtils.hasText(principalKey)) {
            throw new IllegalArgumentException("A2A remote Agent identity requires principalKey");
        }
        return new WorkflowExecutionIdentity(
                Source.A2A_REMOTE_AGENT, tenantId, projectId, projectCode,
                principalKey, true, false);
    }

    /**
     * Runtime-owned non-human identity for a version-pinned Automation.
     * It may resolve project-scoped credentials but is never promoted to a business user.
     */
    public static WorkflowExecutionIdentity fromAutomation(
            String tenantId,
            Long projectId,
            String projectCode,
            String principalId) {
        if (!StringUtils.hasText(principalId)) {
            throw new IllegalArgumentException("Automation identity requires principalId");
        }
        return new WorkflowExecutionIdentity(
                Source.AUTOMATION, tenantId, projectId, projectCode,
                principalId, true, false);
    }

    public static WorkflowExecutionIdentity untrustedDebug() {
        return new WorkflowExecutionIdentity(Source.DEBUG_UNTRUSTED, null, null, null, null, false, false);
    }

    /** Internal signed Studio trial only: project credentials, never a business-user identity. */
    public static WorkflowExecutionIdentity fromAttestedStudioProjectTest(
            Long projectId, String projectCode, String platformActorId) {
        if (projectId == null || projectId <= 0 || !StringUtils.hasText(projectCode)
                || !StringUtils.hasText(platformActorId)) {
            throw new IllegalArgumentException("Attested Studio project test identity is incomplete");
        }
        return new WorkflowExecutionIdentity(Source.STUDIO_PROJECT_TEST, null, projectId,
                projectCode, "platform:" + platformActorId.trim(), true, false);
    }

    /**
     * A remote MCP Client may execute published platform contracts, but it is
     * never promoted to a ReachAI business user for personal-memory or
     * user-ACL resolution.
     */
    public static WorkflowExecutionIdentity fromMcpRemoteClient(
            String tenantId,
            Long projectId,
            String projectCode,
            String principalKey) {
        if (!StringUtils.hasText(principalKey)) {
            throw new IllegalArgumentException("MCP remote client identity requires principalKey");
        }
        return new WorkflowExecutionIdentity(
                Source.MCP_REMOTE_CLIENT, tenantId, projectId, projectCode,
                principalKey, true, false);
    }

    /**
     * Bind an already attested caller to the server-resolved Agent target project.
     * This does not authorize access to that Agent. No business-input map is accepted here.
     * Caller source, tenant and principal retain the canonical factory semantics, including
     * non-human principals and untrusted modes; an absent caller keeps project-only Agent behavior.
     */
    public static WorkflowExecutionIdentity forAgentExecution(
            Long projectId, String projectCode, WorkflowExecutionIdentity caller) {
        if (caller == null) return fromAgent(projectId, projectCode);
        return restoreFromTrustedSnapshot(caller.source, caller.tenantId, projectId, projectCode, caller.userId);
    }

    /**
     * Rebuild identity for Runtime-owned session snapshots. Trust flags are derived only from
     * {@link Source}, never from client-supplied boolean fields.
     */
    public static WorkflowExecutionIdentity restoreFromTrustedSnapshot(
            Source source,
            Long projectId,
            String projectCode,
            String userId) {
        return restoreFromTrustedSnapshot(source, null, projectId, projectCode, userId);
    }

    public static WorkflowExecutionIdentity restoreFromTrustedSnapshot(
            Source source,
            String tenantId,
            Long projectId,
            String projectCode,
            String userId) {
        Source resolved = source == null ? Source.DEBUG_UNTRUSTED : source;
        return switch (resolved) {
            case EMBED_SESSION -> StringUtils.hasText(userId)
                    ? fromEmbedSession(tenantId, projectId, projectCode, userId)
                    : untrustedDebug();
            case AGENT -> StringUtils.hasText(userId)
                    ? fromAgent(tenantId, projectId, projectCode, userId)
                    : fromAgent(tenantId, projectId, projectCode, null);
            case A2A_REMOTE_AGENT -> StringUtils.hasText(userId)
                    ? fromA2aRemoteAgent(tenantId, projectId, projectCode, userId)
                    : untrustedDebug();
            case AUTOMATION -> StringUtils.hasText(userId)
                    ? fromAutomation(tenantId, projectId, projectCode, userId)
                    : untrustedDebug();
            case MCP_REMOTE_CLIENT -> StringUtils.hasText(userId)
                    ? fromMcpRemoteClient(tenantId, projectId, projectCode, userId)
                    : untrustedDebug();
            // A request-scoped Studio trust grant must never survive a generic context snapshot.
            case STUDIO_PROJECT_TEST -> untrustedDebug();
            case DEBUG_UNTRUSTED -> untrustedDebug();
        };
    }

    /**
     * Best-effort restore from Runtime-owned context snapshots. Ignores any projectTrusted/userTrusted
     * fields present in the map — trust is recomputed from source only.
     */
    public static WorkflowExecutionIdentity restoreFromContextMap(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return untrustedDebug();
        }
        Source source = parseSource(raw.get("source"));
        String tenantId = text(raw.get("tenantId"));
        Long projectId = asLong(raw.get("projectId"));
        String projectCode = text(raw.get("projectCode"));
        String userId = text(raw.get("userId"));
        return restoreFromTrustedSnapshot(source, tenantId, projectId, projectCode, userId);
    }

    public Source getSource() {
        return source;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public String getUserId() {
        return userId;
    }

    public boolean isProjectTrusted() {
        return projectTrusted;
    }

    public boolean isUserTrusted() {
        return userTrusted;
    }

    public Source source() {
        return source;
    }

    public String tenantId() {
        return tenantId;
    }

    public Long projectId() {
        return projectId;
    }

    public String projectCode() {
        return projectCode;
    }

    public String userId() {
        return userId;
    }

    public boolean projectTrusted() {
        return projectTrusted;
    }

    public boolean userTrusted() {
        return userTrusted;
    }

    @JsonIgnore
    public boolean canResolveProjectCredential() {
        return projectTrusted && (projectId != null || StringUtils.hasText(projectCode));
    }

    @JsonIgnore
    public boolean canResolveUserAcl() {
        return userTrusted && StringUtils.hasText(userId);
    }

    /**
     * Authorize a PROJECT-scoped credential against this identity.
     * When both id and code are present on credential and caller, both must match.
     * Conflicting id/code pairs are rejected (no OR short-circuit).
     */
    public boolean authorizeProjectCredential(Long credentialProjectId, String credentialProjectCode) {
        if (!canResolveProjectCredential()) {
            return false;
        }
        boolean credHasId = credentialProjectId != null;
        boolean credHasCode = StringUtils.hasText(credentialProjectCode);
        if (!credHasId && !credHasCode) {
            return false;
        }
        boolean idCompared = false;
        boolean idOk = true;
        if (projectId != null && credHasId) {
            idCompared = true;
            idOk = Objects.equals(projectId, credentialProjectId);
        }
        boolean codeCompared = false;
        boolean codeOk = true;
        if (StringUtils.hasText(projectCode) && credHasCode) {
            codeCompared = true;
            codeOk = projectCode.equalsIgnoreCase(credentialProjectCode.trim());
        }
        if (!idCompared && !codeCompared) {
            return false;
        }
        return idOk && codeOk;
    }

    private static Source parseSource(Object raw) {
        if (raw instanceof Source source) {
            return source;
        }
        if (raw == null) {
            return Source.DEBUG_UNTRUSTED;
        }
        try {
            return Source.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Source.DEBUG_UNTRUSTED;
        }
    }

    private static Long asLong(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        return StringUtils.hasText(text) ? text : null;
    }
}
