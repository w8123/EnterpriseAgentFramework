package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import org.springframework.util.StringUtils;

import java.util.Optional;

/** Which node failures may be retried automatically. Fails closed: only known transient failures retry. */
final class RuntimeNodeRetryPolicy {

    private final RuntimeIoNodeHandlers ioNodeHandlers;

    RuntimeNodeRetryPolicy(RuntimeIoNodeHandlers ioNodeHandlers) {
        this.ioNodeHandlers = ioNodeHandlers;
    }

    boolean allowsRetry(GraphSpec.Node node, String nodeType, RuntimeGraphSpecExecutionResult failure) {
        GraphSpec.RetryPolicy retry = node.getRetry();
        if (retry == null || !Boolean.TRUE.equals(retry.getEnabled())) {
            return false;
        }
        if (failure != null) {
            if (failure.success()
                    || failure.isWaitingUser()
                    || WorkflowInteractionCodes.WAITING.equals(failure.code())
                    || "RUNTIME_GRAPH_CANCELLED".equals(failure.code())) {
                return false;
            }
            if (!isRetryableFailure(nodeType, failure)) {
                return false;
            }
        }
        Optional<AgentGraphNodeType> type = AgentGraphNodeType.find(nodeType);
        if (type.isEmpty() || !type.get().retryable()) {
            return false;
        }
        if ("HTTP_REQUEST".equals(nodeType)) {
            if (!ioNodeHandlers.allowsAutomaticHttpRetry(node)) {
                return false;
            }
        }
        return true;
    }

    boolean isRetryableFailure(String nodeType, RuntimeGraphSpecExecutionResult failure) {
        if (failure == null || !StringUtils.hasText(failure.code())) {
            return false;
        }
        String code = failure.code();
        if (code.startsWith("RUNTIME_HTTP_EGRESS_")
                || "RUNTIME_HTTP_CREDENTIAL_DENIED".equals(code)
                || "RUNTIME_HTTP_CREDENTIAL_INVALID".equals(code)
                || "RUNTIME_HTTP_CREDENTIAL_TYPE_UNSUPPORTED".equals(code)
                || "RUNTIME_HTTP_URL_REQUIRED".equals(code)
                || "RUNTIME_HTTP_URL_INVALID".equals(code)
                || "RUNTIME_HTTP_METHOD_UNSUPPORTED".equals(code)
                || "RUNTIME_HTTP_REDIRECT_CREDENTIAL_DENIED".equals(code)
                || "RUNTIME_HTTP_REDIRECT_DOWNGRADE_DENIED".equals(code)
                || "RUNTIME_GRAPH_NODE_UNSUPPORTED".equals(code)
                || code.contains("CONFIG")
                || code.contains("VALIDATION")) {
            return false;
        }
        // Explicit non-retryable configuration / identity / dependency failures.
        if (code.contains("REQUIRED")
                || code.contains("DENIED")
                || code.contains("UNSUPPORTED")
                || code.contains("UNAVAILABLE")
                || code.contains("INVALID")
                || code.contains("PROTECTED")
                || "RUNTIME_KNOWLEDGE_USER_IDENTITY_REQUIRED".equals(code)
                || "RUNTIME_MODEL_REQUIRED".equals(code)
                || "RUNTIME_TOOL_REF_REQUIRED".equals(code)
                || code.startsWith("RUNTIME_KNOWLEDGE_") && code.endsWith("_REQUIRED")
                || code.endsWith("_CLIENT_UNAVAILABLE")
                || code.contains("PERMISSION")
                || code.contains("ACL")) {
            return false;
        }
        Object retryableFlag = failure.metadata() == null ? null : failure.metadata().get("retryableFailure");
        if (Boolean.TRUE.equals(retryableFlag)) {
            return true;
        }
        if ("HTTP_REQUEST".equals(nodeType)) {
            if (code.startsWith("RUNTIME_HTTP_STATUS_")) {
                try {
                    int status = Integer.parseInt(code.substring("RUNTIME_HTTP_STATUS_".length()));
                    return ioNodeHandlers.isRetryableHttpStatus(status);
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
            return "RUNTIME_HTTP_TIMEOUT".equals(code) || "RUNTIME_HTTP_CONNECT_FAILED".equals(code);
        }
        // Default fail-closed: only explicitly marked transient failures are retryable.
        return false;
    }
}
