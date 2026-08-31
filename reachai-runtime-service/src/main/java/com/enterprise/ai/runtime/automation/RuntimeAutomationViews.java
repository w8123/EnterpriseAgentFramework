package com.enterprise.ai.runtime.automation;

import java.util.List;
import java.util.Map;

public final class RuntimeAutomationViews {

    public record UpsertCommand(
            String name,
            String description,
            String tenantId,
            Long projectId,
            String projectCode,
            Long expectedRevision,
            Boolean activate,
            TargetCommand target,
            ScheduleCommand schedule,
            ExecutionPolicyCommand executionPolicy,
            Map<String, Object> input) {
    }

    public record TargetCommand(String type, String id, Long versionId) {
    }

    public record ScheduleCommand(
            String type,
            String cronExpression,
            String fireAt,
            String timeZone,
            String misfirePolicy,
            Integer misfireGraceSeconds,
            Integer maxCatchUp) {
    }

    public record ExecutionPolicyCommand(
            String concurrencyPolicy,
            Integer maxConcurrentRuns,
            Integer timeoutSeconds,
            Integer maxAttempts,
            Integer initialBackoffSeconds,
            Integer maxBackoffSeconds) {
    }

    public record AutomationSummaryView(
            String automationKey,
            String name,
            String description,
            String tenantId,
            Long projectId,
            String projectCode,
            String status,
            long revision,
            String targetType,
            String targetId,
            Long targetVersionId,
            String triggerType,
            String scheduleLabel,
            String timeZone,
            String nextFireAt,
            String lastFireAt,
            String updatedAt) {
    }

    public record AutomationDetailView(
            AutomationSummaryView automation,
            AutomationVersionView currentVersion,
            List<AutomationVersionView> versions,
            List<OccurrenceView> recentOccurrences) {
    }

    public record AutomationVersionView(
            Long id,
            int versionNo,
            String targetType,
            String targetId,
            Long targetVersionId,
            Map<String, Object> targetSnapshot,
            String triggerType,
            String cronExpression,
            String fireAt,
            String timeZone,
            String misfirePolicy,
            int misfireGraceSeconds,
            int maxCatchUp,
            String concurrencyPolicy,
            int maxConcurrentRuns,
            int timeoutSeconds,
            int maxAttempts,
            int initialBackoffSeconds,
            int maxBackoffSeconds,
            Map<String, Object> input,
            String principalType,
            String principalId,
            String fingerprintSha256,
            String createdBy,
            String createdAt) {
    }

    public record OccurrenceView(
            Long id,
            String occurrenceKey,
            String automationKey,
            Long automationVersionId,
            String sourceType,
            String scheduledAt,
            String availableAt,
            String status,
            int attemptCount,
            int maxAttempts,
            String traceId,
            String interactionId,
            String errorCode,
            String errorMessage,
            String startedAt,
            String completedAt,
            String createdAt,
            List<AttemptView> attempts) {
    }

    public record AttemptView(
            Long id,
            int attemptNo,
            String status,
            String workerId,
            String traceId,
            String resultSummary,
            String errorCode,
            String errorMessage,
            String startedAt,
            String endedAt) {
    }

    public record ReadinessView(
            boolean enabled,
            String engine,
            String engineVersion,
            String clockOwnership,
            String executionOwnership,
            String timestampMode,
            String message) {
    }

    private RuntimeAutomationViews() {
    }
}
