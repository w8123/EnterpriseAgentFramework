package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformOpsReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformOpsReadPort.Read;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP Hub operations views for platform AI through the controllers a person uses. Conversations belong to the run
 * center, whose adapter handles them; this one delegates those reads.
 */
@Component
@RequiredArgsConstructor
class McpHubOpsPlatformAdapter implements McpPlatformOpsReadPort {
    private final McpHubOverviewController overview;
    private final McpUsageController usage;
    private final McpCallLogController callLogs;
    private final McpEmployeeConnectionController employeeConnections;
    private final McpWriteApprovalController writeApprovals;
    private final McpPlatformConversationReader conversations;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> read(PlatformAuthenticatedSession actor, Read read, String id, Map<String, Object> query) {
        if (read == Read.CONVERSATIONS || read == Read.CONVERSATION) return conversations.read(actor, read == Read.CONVERSATION ? id : null, query);
        Integer limit = (Integer) query.get("limit");
        Integer offset = (Integer) query.get("offset");
        String projectCode = (String) query.get("projectCode");
        return scope.run(actor, request -> ResponseEntity.ok(switch (read) {
            case MCP_OVERVIEW -> overview.overview(request, (Integer) query.get("days"));
            case MCP_USAGE -> usage.report(request, (Integer) query.get("days"), projectCode);
            case MCP_CALL_LOGS -> callLogs.list(request, (String) query.get("direction"), (Long) query.get("publicationId"), null,
                    (Long) query.get("connectionId"), null, (String) query.get("toolName"), (Boolean) query.get("success"),
                    (String) query.get("errorCategory"), (Integer) query.get("days"), limit, offset);
            case MCP_CALL_LOG -> callLogs.detail(request, Long.parseLong(id));
            case EMPLOYEE_CONNECTIONS -> employeeConnections.list(request, projectCode, (String) query.get("state"),
                    (String) query.get("keyword"), limit, offset);
            case EMPLOYEE_WRITE_APPROVALS -> writeApprovals.list(request, projectCode, (String) query.get("state"),
                    (String) query.get("keyword"), limit, offset);
            case EMPLOYEE_WRITE_APPROVAL -> writeApprovals.get(request, Long.parseLong(id));
            case CONVERSATIONS, CONVERSATION -> throw new IllegalStateException("handled above");
        }));
    }

    @Override
    public ResponseEntity<?> revokeEmployeeConnection(PlatformAuthenticatedSession actor, long connectionId) {
        return scope.run(actor, request -> ResponseEntity.ok(employeeConnections.revoke(request, connectionId)));
    }
}
