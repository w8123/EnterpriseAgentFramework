package com.enterprise.ai.control.mcp.domain.publication;

import com.enterprise.ai.control.mcp.domain.McpDomainException;

import java.time.LocalDateTime;
import java.util.List;

/** Immutable publish snapshot: the only source of truth for tools/list and tools/call. */
public record McpPublicationRevision(
        Long id,
        long publicationId,
        int revisionNo,
        List<McpToolProjection> tools,
        String riskSummaryJson,
        LocalDateTime publishedAt) {

    public McpPublicationRevision {
        if (publicationId <= 0) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", "publicationId is required");
        }
        if (revisionNo < 1) {
            throw new McpDomainException("MCP_REVISION_NO_INVALID", "revisionNo must start at 1");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        long distinctNames = tools.stream().map(McpToolProjection::name).distinct().count();
        if (distinctNames != tools.size()) {
            throw new McpDomainException("MCP_TOOL_NAME_DUPLICATED",
                    "a revision cannot project two tools with the same name");
        }
        if (publishedAt == null) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", "publishedAt is required");
        }
    }

    public boolean hasTool(String toolName) {
        return tools.stream().anyMatch(tool -> tool.name().equals(toolName));
    }

    public McpToolProjection tool(String toolName) {
        return tools.stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new McpDomainException("MCP_TOOL_NOT_IN_REVISION",
                        "tool is not projected in this revision: " + toolName));
    }
}
