package com.enterprise.ai.control.mcp.application.port;

import java.util.Map;

/**
 * Outbound execution boundary from the MCP protocol layer to Runtime.
 * Implementations must sign the exact request bytes (Control→Runtime HMAC).
 */
public interface McpRuntimeExecutionGateway {

    ExecutionOutcome execute(ToolExecutionCommand command);

    /** Executes as the employee behind a personal AI-tool connection (trusted business user). */
    ExecutionOutcome executeAsEmployee(EmployeeToolExecutionCommand command);

    record EmployeeToolExecutionCommand(
            String sourceKind,
            String sourceRef,
            Long workflowVersionId,
            String toolName,
            Map<String, Object> arguments,
            long connectionId,
            String aiToolKind,
            String externalUserId,
            Long projectId,
            String projectCode,
            String tenantId,
            long publicationId,
            int revisionNo,
            String capabilityContractHash) {
    }

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
