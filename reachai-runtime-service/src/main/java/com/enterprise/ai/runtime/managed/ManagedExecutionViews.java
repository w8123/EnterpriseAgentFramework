package com.enterprise.ai.runtime.managed;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ManagedExecutionViews {

    private ManagedExecutionViews() {
    }

    public record CreateRequest(
            String projectCode,
            String sourceType,
            String sourceRef,
            String executorProvider,
            String sandboxProfile,
            String modelRef,
            String acceptanceProfile,
            String objective,
            Integer priority,
            Integer maxWallTimeSeconds,
            Integer approvalTimeoutSeconds) {
    }

    public record CreatedView(ExecutionView execution) {
    }

    public record ExecutionView(
            String executionId,
            String tenantId,
            String projectCode,
            String requestedByUserId,
            String sourceType,
            String sourceRef,
            String executorProvider,
            String sandboxProfile,
            String modelRef,
            String acceptanceProfile,
            String objectiveSha256,
            String status,
            String cleanupStatus,
            String pendingInteractionId,
            String pendingApprovalRequestId,
            int approvalCount,
            int priority,
            int maxWallTimeSeconds,
            int approvalTimeoutSeconds,
            int lastEventSequence,
            boolean cancelRequested,
            String errorCode,
            String errorMessage,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime startedAt,
            LocalDateTime finalizingAt,
            LocalDateTime completedAt) {
    }

    public record WorkerClaimView(
            String schema,
            String executionId,
            String objective,
            String objectiveSha256,
            String workspaceProfile,
            String executorProvider,
            String modelRef,
            String acceptanceProfile,
            int maxWallTimeMs,
            int approvalTimeoutMs,
            LocalDateTime leaseExpiresAt) {
    }

    public record WorkerEventV1(
            String schema,
            String executionId,
            Integer sequence,
            String eventId,
            Instant occurredAt,
            String type,
            String phase,
            String visibility,
            String persistence,
            String message,
            Map<String, Object> data) {
    }

    public record WorkerEventBatchRequest(List<WorkerEventV1> events) {
        public WorkerEventBatchRequest {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    public record WorkerMutationView(
            String executionId,
            String status,
            int lastEventSequence,
            boolean cancelRequested,
            LocalDateTime leaseExpiresAt) {
    }

    public record WorkerCommand(long sequence, String type, Map<String, Object> data) {
    }

    public record WorkerCommandBatchView(long cursor, List<WorkerCommand> commands) {
        public WorkerCommandBatchView {
            commands = commands == null ? List.of() : List.copyOf(commands);
        }
    }

    public record ArtifactDescriptor(
            String artifactId,
            String artifactType,
            String objectKey,
            String sha256,
            Long sizeBytes,
            String mediaType) {
    }

    public record ArtifactUploadView(
            String schema,
            String executionId,
            String artifactId,
            String artifactType,
            String objectKey,
            String sha256,
            long sizeBytes,
            String mediaType,
            String validationStatus,
            String scanStatus) {
    }

    public record ArtifactView(
            String schema,
            String executionId,
            String artifactId,
            String artifactType,
            String sha256,
            long sizeBytes,
            String mediaType,
            String validationStatus,
            String scanStatus,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime retentionExpiresAt) {
    }

    public record WorkerCompleteRequest(
            String outcome,
            String errorCode,
            String errorMessage,
            List<ArtifactDescriptor> artifacts) {
        public WorkerCompleteRequest {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }

    public record CancelRequest(String reason) {
    }

    public record ApprovalDecisionRequest(String decision, String idempotencyKey) {
    }

    public record ApprovalDecisionView(
            String executionId,
            String interactionId,
            String approvalRequestId,
            String decision,
            long commandSequence,
            boolean expired,
            boolean idempotentReplay) {
    }

    public record ApprovalView(
            String schema,
            String executionId,
            String interactionId,
            String approvalRequestId,
            String status,
            JsonNode uiRequest,
            LocalDateTime expiresAt,
            LocalDateTime updatedAt) {
    }
}
