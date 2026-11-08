package com.enterprise.ai.control.mcp.application.usage;

import java.time.LocalDateTime;
import java.util.List;

/** Row-level, body-free records for the audit export. Each list is newest first and already capped. */
public interface AiAuditSource {

    List<ToolCall> toolCalls(LocalDateTime since, String projectCode, int limit);

    List<Approval> approvals(LocalDateTime since, String projectCode, int limit);

    /** Business runs from Runtime (embedded chat, automation, API...); MCP runs are covered by tool calls. */
    List<Run> runs(String projectCode, int days, int limit);

    List<Login> logins(LocalDateTime since, int limit);

    record ToolCall(LocalDateTime at, String projectCode, String toolName, boolean success, String errorCategory,
                    String runId, String appId, String externalUserId, String userName, String toolKind) {
    }

    record Approval(LocalDateTime at, String projectCode, String systemName, String toolName, String toolDescription,
                    String state, String decidedBy, LocalDateTime decidedAt, String errorCode, String runId,
                    String appId, String externalUserId, String userName, String toolKind) {
    }

    record Run(LocalDateTime at, String traceId, String entryType, String status, String projectCode, String appId,
               String externalUserId, String agentName, String workflowName, String errorCode, long tokens) {
    }

    record Login(LocalDateTime at, String eventType, String username, String displayName, String detailsJson) {
    }
}
