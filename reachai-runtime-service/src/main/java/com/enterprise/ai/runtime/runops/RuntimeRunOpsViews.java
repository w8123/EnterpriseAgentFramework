package com.enterprise.ai.runtime.runops;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class RuntimeRunOpsViews {

    private RuntimeRunOpsViews() {
    }

    public record RuntimeRunOpsDetailView(
            RuntimeRunOpsSummaryView summary,
            List<RuntimeRunOpsSpanView> spans,
            List<RuntimeRunOpsToolCallView> toolCalls,
            List<RuntimeRunOpsGuardDecisionView> guardDecisions,
            RuntimeRunOpsSnapshotView snapshot,
            List<RuntimeRunOpsWorkflowPathItemView> workflowPath,
            List<String> repairHints) {
    }

    public record RuntimeRunOpsComparisonView(
            RuntimeRunOpsSummaryView baseline,
            RuntimeRunOpsSummaryView candidate,
            List<RuntimeRunOpsDiffItemView> summaryDiffs,
            List<RuntimeRunOpsSpanDiffView> spanDiffs,
            List<RuntimeRunOpsToolDiffView> toolDiffs,
            List<RuntimeRunOpsGuardDiffView> guardDiffs) {
    }

    public record RuntimeRunOpsDiagnosticsView(
            List<RuntimeRunOpsFailureClusterView> failureClusters,
            List<RuntimeRunOpsVersionComparisonView> versionComparisons) {
    }

    public record RuntimeRunOpsFailureClusterView(
            String agentId,
            String agentName,
            String version,
            Long versionId,
            String runtimeType,
            String runtimePlacement,
            String errorType,
            String nodeId,
            String toolName,
            Integer count,
            Integer fallbackCount,
            Integer avgLatencyMs,
            LocalDateTime firstSeenAt,
            LocalDateTime lastSeenAt,
            String sampleTraceId,
            List<String> traceIds,
            String sampleError,
            List<String> repairHints,
            String workflowId,
            String workflowKeySlug,
            String workflowVersion,
            Long workflowVersionId,
            String sourceType,
            String sourceId) {
    }

    public record RuntimeRunOpsVersionComparisonView(
            String agentId,
            String agentName,
            String version,
            Long versionId,
            String runtimeType,
            String runtimePlacement,
            Integer runCount,
            Integer successCount,
            Integer failureCount,
            Double successRate,
            Integer avgLatencyMs,
            Integer p95LatencyMs,
            Integer avgTokenCost,
            Integer fallbackCount,
            Integer toolErrorCount,
            Integer guardDenyCount,
            String latestTraceId,
            LocalDateTime latestStartedAt,
            String workflowId,
            String workflowKeySlug,
            String workflowVersion,
            Long workflowVersionId,
            String sourceType,
            String sourceId) {
    }

    public record RuntimeRunOpsDiffItemView(
            String field,
            Object baseline,
            Object candidate,
            boolean changed) {
    }

    public record RuntimeRunOpsSpanDiffView(
            String key,
            RuntimeRunOpsSpanView baseline,
            RuntimeRunOpsSpanView candidate,
            List<RuntimeRunOpsDiffItemView> diffs,
            boolean changed) {
    }

    public record RuntimeRunOpsToolDiffView(
            String key,
            RuntimeRunOpsToolCallView baseline,
            RuntimeRunOpsToolCallView candidate,
            List<RuntimeRunOpsDiffItemView> diffs,
            boolean changed) {
    }

    public record RuntimeRunOpsGuardDiffView(
            String key,
            RuntimeRunOpsGuardDecisionView baseline,
            RuntimeRunOpsGuardDecisionView candidate,
            List<RuntimeRunOpsDiffItemView> diffs,
            boolean changed) {
    }

    public record RuntimeRunOpsSummaryView(
            String traceId,
            String status,
            String agentId,
            String agentName,
            String version,
            Long versionId,
            String runtimeType,
            String runtimePlacement,
            String graphCode,
            String sessionId,
            String userId,
            String intentType,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            Integer latencyMs,
            Integer tokenCost,
            Integer nodeCount,
            Integer toolCallCount,
            Integer errorCount,
            boolean fallback,
            String dispatchUrl,
            String fallbackReason,
            String workflowId,
            String workflowKeySlug,
            String workflowVersion,
            Long workflowVersionId,
            String entryAgentId,
            String entryAgentKeySlug,
            String sourceType,
            String sourceId,
            Map<String, Object> metadata) {
    }

    public record RuntimeRunOpsSpanView(
            Long id,
            String spanId,
            String parentSpanId,
            String spanType,
            String runtimeType,
            String nodeId,
            String toolName,
            String status,
            String inputSummary,
            String outputSummary,
            Map<String, Object> metadata,
            String errorCode,
            String errorMessage,
            Integer latencyMs,
            Integer tokenCost,
            LocalDateTime startedAt,
            LocalDateTime endedAt) {
    }

    public record RuntimeRunOpsToolCallView(
            Long id,
            String toolName,
            String agentName,
            String sessionId,
            String userId,
            String intentType,
            String projectCode,
            boolean success,
            String argsJson,
            String resultSummary,
            String errorCode,
            Integer elapsedMs,
            Integer tokenCost,
            LocalDateTime createdAt) {
    }

    public record RuntimeRunOpsGuardDecisionView(
            Long id,
            String decisionType,
            String targetKind,
            String targetName,
            String decision,
            String reason,
            Map<String, Object> metadata,
            LocalDateTime createdAt) {
    }

    public record RuntimeRunOpsSnapshotView(
            String agentId,
            String agentName,
            String keySlug,
            String runtimeType,
            String runtimePlacement,
            Map<String, Object> runtimeConfig,
            Object graphSpec,
            String snapshotJson) {
    }

    public record RuntimeRunOpsWorkflowPathItemView(
            String fromNodeId,
            String toNodeId,
            String condition,
            String route,
            String status,
            String workflowStatus,
            String interactionId,
            String spanId,
            LocalDateTime startedAt,
            LocalDateTime endedAt) {
    }
}
