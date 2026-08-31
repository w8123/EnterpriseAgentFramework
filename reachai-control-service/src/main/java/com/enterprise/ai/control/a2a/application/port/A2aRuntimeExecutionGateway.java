package com.enterprise.ai.control.a2a.application.port;

import com.enterprise.ai.control.a2a.domain.A2aTaskState;

import java.util.List;

public interface A2aRuntimeExecutionGateway {

    ExecutionResult execute(DispatchCommand command);

    CancelResult cancel(CancelCommand command);

    record DispatchCommand(
            String executionId,
            String taskId,
            String contextId,
            String agentId,
            long agentConfigVersionId,
            String tenantScope,
            String principalKey,
            String principalType,
            String trustLevel,
            List<String> scopes,
            String interactionId,
            long timeoutMs,
            byte[] canonicalMessage) {
        public DispatchCommand {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            canonicalMessage = canonicalMessage == null ? null : canonicalMessage.clone();
        }

        @Override
        public byte[] canonicalMessage() {
            return canonicalMessage == null ? null : canonicalMessage.clone();
        }
    }

    record ExecutionResult(
            A2aTaskState state,
            String runtimeRunId,
            String traceId,
            String interactionId,
            String statusSummary,
            String errorCode,
            String errorSummary,
            OutputResource agentMessage,
            OutputResource artifact) {
    }

    record CancelCommand(
            String executionId,
            String tenantScope,
            String principalKey) {
    }

    record CancelResult(boolean accepted, boolean active, boolean queuedBeforeStart) {
    }

    enum FailureKind {
        DEFINITELY_NOT_SENT,
        OUTCOME_UNKNOWN,
        PERMANENT
    }

    final class CallException extends RuntimeException {
        private final FailureKind failureKind;
        private final String code;

        public CallException(FailureKind failureKind, String code, String message, Throwable cause) {
            super(message, cause);
            this.failureKind = failureKind;
            this.code = code;
        }

        public FailureKind failureKind() {
            return failureKind;
        }

        public String code() {
            return code;
        }
    }

    record OutputResource(
            String resourceId,
            byte[] canonicalJson,
            String sha256,
            long bytes,
            List<String> mediaTypes,
            String safeSummary) {
        public OutputResource {
            canonicalJson = canonicalJson == null ? null : canonicalJson.clone();
            mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        }

        @Override
        public byte[] canonicalJson() {
            return canonicalJson == null ? null : canonicalJson.clone();
        }
    }
}
