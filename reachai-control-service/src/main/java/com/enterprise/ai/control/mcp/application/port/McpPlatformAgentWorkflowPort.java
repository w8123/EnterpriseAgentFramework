package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** Agent-wide Workflow operations: move the live config to the newest Workflow versions, or add a knowledge Q&A Workflow. */
public interface McpPlatformAgentWorkflowPort {
    ResponseEntity<?> publishLatest(PlatformAuthenticatedSession actor, String agentId, long expectedActiveConfigVersionId);
    ResponseEntity<?> knowledgeQa(PlatformAuthenticatedSession actor, String agentId, boolean attachToAgent, String knowledgeBaseCode,
                                  String knowledgeBaseName, String modelInstanceId);
}
