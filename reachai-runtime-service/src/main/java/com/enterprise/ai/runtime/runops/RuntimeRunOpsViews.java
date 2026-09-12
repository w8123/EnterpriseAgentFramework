package com.enterprise.ai.runtime.runops;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Public RunOps read models.
 *
 * <p>{@code runtime_run} is the root execution fact. Spans, tool calls and
 * guard decisions are child evidence and never manufacture a root run.</p>
 */
public final class RuntimeRunOpsViews {

    private RuntimeRunOpsViews() {
    }

    public record RuntimeRunOpsDetailView(
            RuntimeRunOpsSummaryView summary,
            List<RuntimeRunOpsSpanView> spans,
            List<RuntimeRunOpsToolCallView> toolCalls,
            List<RuntimeRunOpsGuardDecisionView> guardDecisions,
            RuntimeRunOpsSnapshotView snapshot,
            List<RuntimeRunOpsExecutionPathItemView> executionPath,
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
            String versionType,
            String agentId,
            String agentName,
            Long agentConfigVersionId,
            Integer agentConfigVersion,
            String workflowId,
            String workflowName,
            Long workflowVersionId,
            String workflowVersion,
            String runtimeType,
            String errorCode,
            String errorMessage,
            String spanType,
            String nodeId,
            String toolName,
            Integer count,
            Integer avgLatencyMs,
            LocalDateTime firstSeenAt,
            LocalDateTime lastSeenAt,
            String sampleTraceId,
            List<String> traceIds,
            List<String> repairHints) {
    }

    public record RuntimeRunOpsVersionComparisonView(
            String versionType,
            String agentId,
            String agentName,
            Long agentConfigVersionId,
            Integer agentConfigVersion,
            String workflowId,
            String workflowName,
            Long workflowVersionId,
            String workflowVersion,
            String runtimeType,
            Integer runCount,
            Integer successCount,
            Integer failureCount,
            Double successRate,
            Integer avgLatencyMs,
            Integer p95LatencyMs,
            Integer avgTokenCost,
            Integer workflowCallCount,
            Integer toolErrorCount,
            Integer guardDenyCount,
            Integer replanCount,
            String latestTraceId,
            LocalDateTime latestStartedAt) {
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
            String runType,
            String entryType,
            String status,
            String suspensionReason,
            String projectCode,
            String tenantId,
            String sessionId,
            String userId,
            String agentId,
            String agentKeySlug,
            String agentName,
            Long agentConfigVersionId,
            Integer agentConfigVersion,
            String workflowId,
            String workflowKeySlug,
            String workflowName,
            Long workflowVersionId,
            String workflowVersion,
            String runtimeType,
            String inputSummary,
            String outputSummary,
            String errorCode,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            Integer latencyMs,
            Integer tokenCost,
            Integer planCount,
            Integer replanCount,
            Integer workflowCallCount,
            Integer toolCallCount,
            Integer guardDenyCount,
            Integer approvalCount,
            String replayOfTraceId,
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
            LocalDateTime createdAt,
            String status,
            String statusSourceSpanId) {
        /** success is the immutable observation; status is the current correlated outcome. */
        public RuntimeRunOpsToolCallView(Long id, String toolName, String agentName, String sessionId,
                                        String userId, String intentType, String projectCode, boolean success,
                                        String argsJson, String resultSummary, String errorCode,
                                        Integer elapsedMs, Integer tokenCost, LocalDateTime createdAt) {
            this(id, toolName, agentName, sessionId, userId, intentType, projectCode, success,
                    argsJson, resultSummary, errorCode, elapsedMs, tokenCost, createdAt,
                    success ? "SUCCESS" : "FAILED", null);
        }
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
            String runType,
            String agentId,
            String agentKeySlug,
            String agentName,
            Long agentConfigVersionId,
            Integer agentConfigVersion,
            String workflowId,
            String workflowKeySlug,
            String workflowName,
            Long workflowVersionId,
            String workflowVersion,
            String runtimeType,
            Map<String, Object> snapshot,
            String snapshotJson) {
    }

    public record RuntimeRunOpsExecutionPathItemView(
            String spanId,
            String parentSpanId,
            Integer depth,
            String spanType,
            String label,
            String status,
            String nodeId,
            String toolName,
            String runtimeType,
            String fromNodeId,
            String toNodeId,
            String condition,
            String route,
            String workflowStatus,
            String interactionId,
            LocalDateTime startedAt,
            LocalDateTime endedAt) {
    }
}
