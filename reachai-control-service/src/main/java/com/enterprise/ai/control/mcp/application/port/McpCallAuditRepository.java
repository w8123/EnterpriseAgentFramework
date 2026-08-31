package com.enterprise.ai.control.mcp.application.port;

import java.time.LocalDateTime;

/** Append-only writer for the bidirectional MCP call audit log. */
public interface McpCallAuditRepository {

    void append(Entry entry);

    record Entry(
            String direction,
            Long publicationId,
            Long clientId,
            String clientName,
            Long remoteServerId,
            String method,
            String toolName,
            Long projectId,
            String projectCode,
            String environment,
            String tenantId,
            boolean success,
            Long latencyMs,
            String errorCategory,
            String requestBody,
            String responseBody,
            String errorMessage,
            String traceId,
            String runId,
            String remoteIp,
            LocalDateTime createdAt) {
    }
}
