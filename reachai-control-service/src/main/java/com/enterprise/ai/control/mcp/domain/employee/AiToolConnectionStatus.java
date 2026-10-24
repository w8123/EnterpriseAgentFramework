package com.enterprise.ai.control.mcp.domain.employee;

/** REVOKED and EXPIRED are terminal; a connection is never re-activated. */
public enum AiToolConnectionStatus {
    ACTIVE,
    REVOKED,
    EXPIRED
}
