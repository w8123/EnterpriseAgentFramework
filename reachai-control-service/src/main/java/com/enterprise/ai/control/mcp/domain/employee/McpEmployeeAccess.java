package com.enterprise.ai.control.mcp.domain.employee;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.McpDomainText;

/**
 * Marks one publication as the set of capabilities employees of a business
 * system (project) may use from their own AI tools. At most one per project.
 */
public record McpEmployeeAccess(
        Long id,
        long publicationId,
        /** The business system's project id; ACL rules scoped to a project match on it. */
        Long projectId,
        String projectCode,
        String displayName,
        boolean enabled,
        /** Write (non-READ) tools wait for an approver before they run as the employee. */
        boolean writeApprovalRequired) {

    public McpEmployeeAccess {
        if (publicationId <= 0) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", "publicationId is required");
        }
        projectCode = McpDomainText.requireText(projectCode, "projectCode");
        displayName = McpDomainText.optionalText(displayName);
    }

    public McpEmployeeAccess(Long id, long publicationId, Long projectId, String projectCode, String displayName,
                             boolean enabled) {
        this(id, publicationId, projectId, projectCode, displayName, enabled, true);
    }

    public McpEmployeeAccess(Long id, long publicationId, String projectCode, String displayName, boolean enabled) {
        this(id, publicationId, null, projectCode, displayName, enabled, true);
    }

    public String label() {
        return displayName.isEmpty() ? projectCode : displayName;
    }
}
