package com.enterprise.ai.runtime.execution.identity;

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
        DEBUG_UNTRUSTED,
        COMPOSITION_UNTRUSTED
    }

    private final Source source;
    private final Long projectId;
    private final String projectCode;
    private final String userId;
    private final boolean projectTrusted;
    private final boolean userTrusted;

    private WorkflowExecutionIdentity(
            Source source,
            Long projectId,
            String projectCode,
            String userId,
            boolean projectTrusted,
            boolean userTrusted) {
        this.source = source == null ? Source.DEBUG_UNTRUSTED : source;
        this.projectId = projectId;
        this.projectCode = StringUtils.hasText(projectCode) ? projectCode.trim() : null;
        this.userId = StringUtils.hasText(userId) ? userId.trim() : null;
        this.projectTrusted = projectTrusted;
        this.userTrusted = userTrusted;
    }

    public static WorkflowExecutionIdentity fromAgent(Long projectId, String projectCode) {
        return new WorkflowExecutionIdentity(Source.AGENT, projectId, projectCode, null, true, false);
    }

    public static WorkflowExecutionIdentity fromAgent(Long projectId, String projectCode, String trustedUserId) {
        boolean userTrusted = StringUtils.hasText(trustedUserId);
        return new WorkflowExecutionIdentity(
                Source.AGENT, projectId, projectCode, trustedUserId, true, userTrusted);
    }

    public static WorkflowExecutionIdentity fromEmbedSession(Long projectId, String projectCode, String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("Embed session identity requires userId");
        }
        return new WorkflowExecutionIdentity(Source.EMBED_SESSION, projectId, projectCode, userId, true, true);
    }

    public static WorkflowExecutionIdentity untrustedDebug() {
        return new WorkflowExecutionIdentity(Source.DEBUG_UNTRUSTED, null, null, null, false, false);
    }

    public static WorkflowExecutionIdentity untrustedComposition() {
        return new WorkflowExecutionIdentity(Source.COMPOSITION_UNTRUSTED, null, null, null, false, false);
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
        Source resolved = source == null ? Source.DEBUG_UNTRUSTED : source;
        return switch (resolved) {
            case EMBED_SESSION -> StringUtils.hasText(userId)
                    ? fromEmbedSession(projectId, projectCode, userId)
                    : untrustedDebug();
            case AGENT -> StringUtils.hasText(userId)
                    ? fromAgent(projectId, projectCode, userId)
                    : fromAgent(projectId, projectCode);
            case COMPOSITION_UNTRUSTED -> untrustedComposition();
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
        Long projectId = asLong(raw.get("projectId"));
        String projectCode = text(raw.get("projectCode"));
        String userId = text(raw.get("userId"));
        return restoreFromTrustedSnapshot(source, projectId, projectCode, userId);
    }

    public Source getSource() {
        return source;
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
