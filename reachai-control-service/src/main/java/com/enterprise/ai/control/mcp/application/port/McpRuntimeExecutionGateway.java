package com.enterprise.ai.control.mcp.application.port;

import java.util.Map;

/**
 * Outbound execution boundary from the MCP protocol layer to Runtime.
 * Implementations must sign the exact request bytes (Control→Runtime HMAC).
 */
public interface McpRuntimeExecutionGateway {

    ExecutionOutcome execute(ToolExecutionCommand command);

    record ToolExecutionCommand(
            String sourceKind,
            String sourceRef,
            Long workflowVersionId,
            String toolName,
            Map<String, Object> arguments,
            long mcpClientId,
            String mcpClientName,
            Long projectId,
            String projectCode,
            String environment,
            String tenantId,
            long publicationId,
            int revisionNo,
            String capabilityContractHash) {
        public ToolExecutionCommand(String sourceKind, String sourceRef, Long workflowVersionId,
                String toolName, Map<String, Object> arguments, long mcpClientId, String mcpClientName,
                Long projectId, String projectCode, String environment, String tenantId,
                long publicationId, int revisionNo) {
            this(sourceKind, sourceRef, workflowVersionId, toolName, arguments, mcpClientId, mcpClientName,
                    projectId, projectCode, environment, tenantId, publicationId, revisionNo, null);
        }
    }

    record ExecutionOutcome(
            boolean success,
            String code,
            Map<String, Object> output,
            String runId,
            String traceId,
            String errorCode) {
    }
}
