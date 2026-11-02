package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Operations views through the console controllers that own them: MCP Hub overview, usage, call logs, employee AI
 * connections and employee write approvals check mcp-hub permissions (call bodies need mcp-hub:payload:read); run center
 * conversations check runops:read on the conversation's project.
 */
public interface McpPlatformOpsReadPort {
    enum Read {
        MCP_OVERVIEW, MCP_USAGE, MCP_CALL_LOGS, MCP_CALL_LOG, EMPLOYEE_CONNECTIONS, EMPLOYEE_WRITE_APPROVALS, EMPLOYEE_WRITE_APPROVAL,
        CONVERSATIONS, CONVERSATION
    }
    ResponseEntity<?> read(PlatformAuthenticatedSession actor, Read read, String id, Map<String, Object> query);
    /** Disconnects one employee's personal AI tool connection, as the console's "disconnect" button. */
    ResponseEntity<?> revokeEmployeeConnection(PlatformAuthenticatedSession actor, long connectionId);
}
