package com.enterprise.ai.control.mcp.application.calllog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read model for the MCP call log list. Bodies are excluded by default; the
 * detail endpoint returns them only under the payload-read permission.
 */
public interface McpCallLogReader {

    Page page(String direction, Long publicationId, Long clientId, String method,
              String toolName, Boolean success, String errorCategory, Integer days,
              int limit, int offset);

    Entry detail(long id);

    record Entry(
            Long id,
            String direction,
            Long publicationId,
            Long clientId,
            String clientName,
            String method,
            String toolName,
            Long projectId,
            String projectCode,
            String environment,
            String tenantId,
            Boolean success,
            Long latencyMs,
            String errorCategory,
            String requestBody,
            String responseBody,
            String errorMessage,
            String traceId,
            String runId,
            String remoteIp,
            LocalDateTime createdAt) {

        public Entry withoutBodies() {
            return new Entry(id, direction, publicationId, clientId, clientName, method,
                    toolName, projectId, projectCode, environment, tenantId,
                    success, latencyMs, errorCategory, null, null, errorMessage,
                    traceId, runId, remoteIp, createdAt);
        }
    }

    record Page(List<Entry> items, long total, int limit, int offset) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
