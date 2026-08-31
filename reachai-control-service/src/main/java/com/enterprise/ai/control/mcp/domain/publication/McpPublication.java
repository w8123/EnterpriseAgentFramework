package com.enterprise.ai.control.mcp.domain.publication;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.McpDomainText;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/** Aggregate root of the MCP Hub outbound model: one externally callable MCP service. */
public record McpPublication(
        Long id,
        String name,
        String description,
        McpPublicationStatus state,
        Long currentRevisionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{1,127}");

    public McpPublication {
        name = McpDomainText.requireText(name, "name");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new McpDomainException("MCP_PUBLICATION_NAME_INVALID",
                    "name must use 2-128 letters, digits, underscores, or hyphens and start with a letter");
        }
        description = McpDomainText.optionalText(description);
        if (state == null) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", "state is required");
        }
    }

    public static McpPublication draft(String name, String description) {
        return new McpPublication(null, name, description, McpPublicationStatus.DRAFT, null, null, null);
    }

    public McpPublication rename(String newName, String newDescription) {
        if (state.terminal()) {
            throw archived();
        }
        return new McpPublication(id, newName, newDescription, state, currentRevisionId, createdAt, updatedAt);
    }

    public McpPublication validating() {
        return transitionTo(McpPublicationStatus.VALIDATING, currentRevisionId, updatedAt);
    }

    public McpPublication ready() {
        return transitionTo(McpPublicationStatus.READY, currentRevisionId, updatedAt);
    }

    public McpPublication publish(long revisionId, LocalDateTime now) {
        if (revisionId <= 0) {
            throw new McpDomainException("MCP_PUBLICATION_NO_REVISION",
                    "a publication requires a persisted revision before publishing");
        }
        return transitionTo(McpPublicationStatus.PUBLISHED, revisionId, now);
    }

    public McpPublication suspend(LocalDateTime now) {
        return transitionTo(McpPublicationStatus.SUSPENDED, currentRevisionId, now);
    }

    public McpPublication resume(LocalDateTime now) {
        if (currentRevisionId == null) {
            throw new McpDomainException("MCP_PUBLICATION_NO_REVISION",
                    "a suspended publication without a current revision cannot be resumed");
        }
        return transitionTo(McpPublicationStatus.PUBLISHED, currentRevisionId, now);
    }

    public McpPublication archive(LocalDateTime now) {
        if (state.terminal()) {
            return this;
        }
        return transitionTo(McpPublicationStatus.ARCHIVED, currentRevisionId, now);
    }

    /** Rollback changes only the effective revision pointer, never a snapshot or lifecycle state. */
    public McpPublication rollbackTo(long revisionId, LocalDateTime now) {
        if (state != McpPublicationStatus.PUBLISHED && state != McpPublicationStatus.SUSPENDED) {
            throw new McpDomainException("MCP_PUBLICATION_ROLLBACK_NOT_ALLOWED",
                    "only a published or suspended publication can roll back a revision");
        }
        if (revisionId <= 0) {
            throw new McpDomainException("MCP_PUBLICATION_NO_REVISION",
                    "rollback requires a persisted revision");
        }
        return new McpPublication(id, name, description, state,
                revisionId, createdAt, now);
    }

    private McpPublication transitionTo(McpPublicationStatus next,
                                        Long revisionId,
                                        LocalDateTime now) {
        if (!state.canTransitionTo(next)) {
            throw new McpDomainException("MCP_PUBLICATION_TRANSITION_INVALID",
                    "publication cannot transition from " + state + " to " + next);
        }
        return new McpPublication(id, name, description, next,
                revisionId, createdAt, now);
    }

    private McpDomainException archived() {
        return new McpDomainException("MCP_PUBLICATION_ARCHIVED",
                "an archived publication cannot transition");
    }
}
