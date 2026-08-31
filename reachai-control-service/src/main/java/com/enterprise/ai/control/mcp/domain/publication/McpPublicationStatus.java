package com.enterprise.ai.control.mcp.domain.publication;

import com.enterprise.ai.control.mcp.domain.McpDomainText;

public enum McpPublicationStatus {
    DRAFT,
    VALIDATING,
    READY,
    PUBLISHED,
    SUSPENDED,
    ARCHIVED;

    public boolean terminal() {
        return this == ARCHIVED;
    }

    /**
     * Legal lifecycle edges for one publication. PUBLISHED may re-enter
     * validation when a new immutable revision is prepared; SUSPENDED may only
     * resume the current revision or archive.
     */
    public boolean canTransitionTo(McpPublicationStatus next) {
        if (next == null || next == this) {
            return false;
        }
        return switch (this) {
            case DRAFT -> next == VALIDATING || next == ARCHIVED;
            case VALIDATING -> next == READY || next == DRAFT || next == ARCHIVED;
            case READY -> next == PUBLISHED || next == DRAFT || next == ARCHIVED;
            case PUBLISHED -> next == VALIDATING || next == SUSPENDED || next == ARCHIVED;
            case SUSPENDED -> next == PUBLISHED || next == ARCHIVED;
            case ARCHIVED -> false;
        };
    }

    public static McpPublicationStatus parse(String value) {
        return McpDomainText.parseEnum(McpPublicationStatus.class, value, "publicationStatus");
    }
}
