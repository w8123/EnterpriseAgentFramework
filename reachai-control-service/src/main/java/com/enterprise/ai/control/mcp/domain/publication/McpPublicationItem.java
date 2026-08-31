package com.enterprise.ai.control.mcp.domain.publication;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.McpDomainText;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/** Draft composition unit of a publication; resolved and frozen into the revision at publish time. */
public record McpPublicationItem(
        Long id,
        long publicationId,
        McpPublicationItemKind sourceKind,
        String sourceRef,
        String alias,
        String descriptionOverride,
        String riskLevelOverride,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public McpPublicationItem {
        if (publicationId <= 0) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", "publicationId is required");
        }
        if (sourceKind == null) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", "sourceKind is required");
        }
        sourceRef = McpDomainText.requireText(sourceRef, "sourceRef");
        alias = McpDomainText.optionalText(alias);
        descriptionOverride = McpDomainText.optionalText(descriptionOverride);
        riskLevelOverride = riskLevelOverride == null || riskLevelOverride.isBlank()
                ? null : riskLevelOverride.trim().toUpperCase(Locale.ROOT);
        if (riskLevelOverride != null
                && !Set.of("READ", "WRITE", "PAGE_ACTION", "IRREVERSIBLE").contains(riskLevelOverride)) {
            throw new McpDomainException("MCP_INVALID_ENUM", "riskLevelOverride is invalid");
        }
    }
}
