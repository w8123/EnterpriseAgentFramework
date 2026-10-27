package com.enterprise.ai.control.mcp.domain.employee;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.McpDomainText;

import java.time.LocalDateTime;

/**
 * A write call an employee's AI tool asked for, frozen until an approver lets it
 * run as that employee or turns it down. Arguments are stored exactly as sent;
 * approvers cannot edit them.
 */
public record McpWriteApproval(
        Long id,
        String tenantId,
        long connectionId,
        Long businessUserId,
        String externalUserId,
        String userName,
        String toolKind,
        Long projectId,
        String projectCode,
        String systemName,
        long publicationId,
        int revisionNo,
        String toolName,
        String toolDescription,
        String sourceKind,
        String sourceRef,
        Long workflowVersionId,
        String capabilityContractHash,
        String riskLevel,
        String argumentsJson,
        String argumentsHash,
        McpWriteApprovalStatus state,
        String decidedBy,
        LocalDateTime decidedAt,
        String decisionComment,
        LocalDateTime executedAt,
        String resultJson,
        String errorCode,
        String runId,
        String traceId,
        LocalDateTime expiresAt,
        LocalDateTime createdAt) {

    public McpWriteApproval {
        tenantId = McpDomainText.requireText(tenantId, "tenantId");
        if (connectionId <= 0 || businessUserId == null) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", "connection and business user are required");
        }
        externalUserId = McpDomainText.requireText(externalUserId, "externalUserId");
        projectCode = McpDomainText.requireText(projectCode, "projectCode");
        toolName = McpDomainText.requireText(toolName, "toolName");
        sourceKind = McpDomainText.requireText(sourceKind, "sourceKind");
        sourceRef = McpDomainText.requireText(sourceRef, "sourceRef");
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        argumentsHash = McpDomainText.requireText(argumentsHash, "argumentsHash");
        if (state == null) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", "state is required");
        }
    }

    /** Pending past its deadline: no longer decidable, shown as expired. */
    public boolean lapsed(LocalDateTime now) {
        return state == McpWriteApprovalStatus.PENDING && expiresAt != null && !now.isBefore(expiresAt);
    }

    /** The state as people see it right now (a lapsed PENDING reads EXPIRED). */
    public McpWriteApprovalStatus visibleState(LocalDateTime now) {
        return lapsed(now) ? McpWriteApprovalStatus.EXPIRED : state;
    }
}
