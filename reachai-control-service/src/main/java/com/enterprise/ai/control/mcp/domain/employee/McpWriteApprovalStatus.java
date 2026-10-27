package com.enterprise.ai.control.mcp.domain.employee;

/** PENDING → EXECUTING → SUCCEEDED / FAILED, or PENDING → REJECTED / EXPIRED. Only PENDING is open. */
public enum McpWriteApprovalStatus {
    PENDING,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    REJECTED,
    EXPIRED
}
