package com.enterprise.ai.control.mcp.domain.publication;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.McpDomainText;

import java.util.regex.Pattern;

/**
 * Frozen tool projection inside a publication revision: the contract external MCP
 * callers see in tools/list, plus the execution binding the protocol layer uses
 * at tools/call time. WORKFLOW projections pin the workflow version at publish
 * time so later edits never drift an already published revision.
 */
public record McpToolProjection(
        String name,
        String description,
        String inputSchemaJson,
        McpPublicationItemKind sourceKind,
        String sourceRef,
        Long workflowVersionId,
        String riskLevel) {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{0,127}");

    public McpToolProjection {
        name = McpDomainText.requireText(name, "tool name");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new McpDomainException("MCP_TOOL_NAME_INVALID",
                    "tool name must use 1-128 letters, digits, underscores, or hyphens and start with a letter");
        }
        description = McpDomainText.optionalText(description);
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
            throw new McpDomainException("MCP_TOOL_SCHEMA_REQUIRED",
                    "tool " + name + " requires a frozen input schema");
        }
        if (sourceKind == null) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", "sourceKind is required");
        }
        sourceRef = McpDomainText.requireText(sourceRef, "sourceRef");
        riskLevel = McpDomainText.optionalText(riskLevel);
        if (sourceKind == McpPublicationItemKind.WORKFLOW && workflowVersionId == null) {
            throw new McpDomainException("MCP_WORKFLOW_VERSION_REQUIRED",
                    "WORKFLOW projection " + name + " requires a pinned workflow version");
        }
    }
}
