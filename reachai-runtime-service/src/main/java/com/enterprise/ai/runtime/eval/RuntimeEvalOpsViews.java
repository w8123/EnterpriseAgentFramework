package com.enterprise.ai.runtime.eval;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

record RuntimeEvalDatasetSummaryView(
        Long id,
        String tenantId,
        String projectCode,
        String targetType,
        String targetId,
        String name,
        String description,
        String source,
        String status,
        Long currentVersionId,
        Integer versionCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

record RuntimeEvalDatasetItemView(
        Long id,
        Long datasetVersionId,
        String itemKey,
        String message,
        Map<String, Object> input,
        Map<String, Object> expected,
        Map<String, Object> metadata,
        Object tags,
        String sourceTraceId,
        Boolean enabled,
        Integer ordinalNo,
        String contentSha256) {
}

record RuntimeEvalDatasetVersionView(
        Long id,
        Long datasetId,
        Integer versionNo,
        String status,
        String fingerprintSha256,
        Integer itemCount,
        String changeNote,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime publishedAt,
        List<RuntimeEvalDatasetItemView> items) {
}

record RuntimeEvalDatasetDetailView(
        RuntimeEvalDatasetSummaryView dataset,
        RuntimeEvalDatasetVersionView currentVersion,
        List<RuntimeEvalDatasetVersionView> versions) {
}

record RuntimeEvalEvaluatorSuiteVersionView(
        Long id,
        String tenantId,
        String name,
        Integer versionNo,
        String status,
        Map<String, Object> config,
        String fingerprintSha256,
        String createdBy,
        LocalDateTime createdAt) {
}

record RuntimeEvalExperimentSummaryView(
        Long id,
        String tenantId,
        String projectCode,
        String targetType,
        String targetId,
        String name,
        Long datasetVersionId,
        Long evaluatorSuiteVersionId,
        Integer repeatCount,
        String status,
        Integer variantCount,
        Integer taskCount,
        Integer completedTaskCount,
        Integer failedTaskCount,
        String gateStatus,
        Map<String, Object> summary,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {
}

record RuntimeEvalExperimentVariantView(
        Long id,
        Long experimentId,
        String variantKey,
        String displayName,
        String variantRole,
        Long targetSnapshotId,
        Long targetConfigVersionId,
        String targetConfigStatus,
        String targetFingerprint,
        String status,
        Map<String, Object> summary) {
}

record RuntimeEvalExperimentItemView(
        Long id,
        Long experimentId,
        Long variantId,
        Long datasetItemId,
        Integer repeatNo,
        String status,
        Boolean runtimeSuccess,
        Boolean assertionPassed,
        Double score,
        Integer elapsedMs,
        String answer,
        String traceId,
        Map<String, Object> executionMetadata,
        List<Map<String, Object>> evaluatorResults,
        String errorCode,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {
}

record RuntimeEvalScoreView(
        Long id,
        Long experimentItemId,
        String evaluatorKey,
        String evaluatorType,
        Double score,
        Boolean passed,
        Double weight,
        String reason,
        Map<String, Object> metadata) {
}

record RuntimeEvalExperimentDetailView(
        RuntimeEvalExperimentSummaryView experiment,
        RuntimeEvalDatasetVersionView datasetVersion,
        List<RuntimeEvalExperimentVariantView> variants,
        List<RuntimeEvalExperimentItemView> items,
        List<RuntimeEvalScoreView> scores,
        long resultCount,
        boolean resultsTruncated) {
}

record RuntimeEvalExperimentItemPage(
        long total,
        int page,
        int pageSize,
        List<RuntimeEvalExperimentItemView> items) {
}
