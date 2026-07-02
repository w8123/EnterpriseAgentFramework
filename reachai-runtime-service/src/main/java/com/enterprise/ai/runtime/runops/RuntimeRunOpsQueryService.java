package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsComparisonView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDiagnosticsView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDiffItemView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsFailureClusterView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsGuardDiffView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsGuardDecisionView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSnapshotView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSpanDiffView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSpanView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsToolDiffView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsToolCallView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsVersionComparisonView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsWorkflowPathItemView;
import com.enterprise.ai.runtime.trace.RuntimeAgentTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeAgentTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RuntimeRunOpsQueryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeToolCallLogMapper toolLogMapper;
    private final RuntimeAgentTraceSpanMapper spanMapper;
    private final RuntimeGuardDecisionLogMapper guardDecisionMapper;
    private final ObjectMapper objectMapper;

    public RuntimeRunOpsDetailView detail(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            throw new IllegalArgumentException("traceId 不能为空");
        }
        String normalizedTraceId = traceId.trim();
        List<RuntimeToolCallLogEntity> toolLogs = findToolLogs(normalizedTraceId);
        List<RuntimeAgentTraceSpanEntity> spans = findSpans(normalizedTraceId);
        List<RuntimeGuardDecisionLogEntity> guardDecisions = findGuardDecisions(normalizedTraceId);
        if (toolLogs.isEmpty() && spans.isEmpty() && guardDecisions.isEmpty()) {
            throw new IllegalArgumentException("RunOps 运行记录不存在: " + normalizedTraceId);
        }
        Map<String, Object> metadata = mergedMetadata(spans);
        RuntimeRunOpsSummaryView summary = buildSummary(normalizedTraceId, toolLogs, spans, guardDecisions, metadata);
        List<RuntimeRunOpsSpanView> spanViews = spans.stream()
                .sorted(Comparator
                        .comparing(RuntimeAgentTraceSpanEntity::getStartedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                        .thenComparing(RuntimeAgentTraceSpanEntity::getId, Comparator.nullsLast(Long::compareTo)))
                .map(this::toSpanView)
                .toList();
        return new RuntimeRunOpsDetailView(
                summary,
                spanViews,
                toolLogs.stream().map(this::toToolCallView).toList(),
                guardDecisions.stream().map(this::toGuardDecisionView).toList(),
                null,
                workflowPath(spanViews),
                repairHints(summary, spanViews));
    }

    public List<RuntimeRunOpsSummaryView> recent(String userId, int limit, int days) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeDays = Math.max(1, Math.min(days, 30));
        int rawRowCap = Math.max(safeLimit * 50, 500);
        LambdaQueryWrapper<RuntimeToolCallLogEntity> wrapper = new LambdaQueryWrapper<RuntimeToolCallLogEntity>()
                .isNotNull(RuntimeToolCallLogEntity::getTraceId)
                .ge(RuntimeToolCallLogEntity::getCreateTime, LocalDateTime.now().minusDays(safeDays))
                .orderByDesc(RuntimeToolCallLogEntity::getId)
                .last("limit " + rawRowCap);
        if (StringUtils.hasText(userId)) {
            wrapper.eq(RuntimeToolCallLogEntity::getUserId, userId.trim());
        }
        List<RuntimeToolCallLogEntity> logs = safeList(toolLogMapper.selectList(wrapper));
        LinkedHashMap<String, List<RuntimeToolCallLogEntity>> grouped = new LinkedHashMap<>();
        for (RuntimeToolCallLogEntity log : logs) {
            String rowTraceId = log.getTraceId();
            if (!StringUtils.hasText(rowTraceId)) {
                continue;
            }
            if (!grouped.containsKey(rowTraceId) && grouped.size() >= safeLimit) {
                continue;
            }
            grouped.computeIfAbsent(rowTraceId, key -> new ArrayList<>()).add(log);
        }
        return grouped.entrySet().stream()
                .map(entry -> summaryFromToolLogs(entry.getKey(), entry.getValue()))
                .toList();
    }

    public RuntimeRunOpsComparisonView compare(String baselineTraceId, String candidateTraceId) {
        RuntimeRunOpsDetailView baseline = detail(baselineTraceId);
        RuntimeRunOpsDetailView candidate = detail(candidateTraceId);
        return new RuntimeRunOpsComparisonView(
                baseline.summary(),
                candidate.summary(),
                summaryDiffs(baseline.summary(), candidate.summary()),
                spanDiffs(baseline.spans(), candidate.spans()),
                toolDiffs(baseline.toolCalls(), candidate.toolCalls()),
                guardDiffs(baseline.guardDecisions(), candidate.guardDecisions()));
    }

    public RuntimeRunOpsDiagnosticsView diagnostics(String userId, int limit, int days) {
        List<RuntimeRunOpsDetailView> details = recent(userId, limit, days).stream()
                .map(RuntimeRunOpsSummaryView::traceId)
                .filter(StringUtils::hasText)
                .map(this::detail)
                .toList();
        return new RuntimeRunOpsDiagnosticsView(failureClusters(details), versionComparisons(details));
    }

    private List<RuntimeToolCallLogEntity> findToolLogs(String traceId) {
        return safeList(toolLogMapper.selectList(new LambdaQueryWrapper<RuntimeToolCallLogEntity>()
                .eq(RuntimeToolCallLogEntity::getTraceId, traceId)
                .orderByAsc(RuntimeToolCallLogEntity::getId)));
    }

    private List<RuntimeAgentTraceSpanEntity> findSpans(String traceId) {
        return safeList(spanMapper.selectList(new LambdaQueryWrapper<RuntimeAgentTraceSpanEntity>()
                .eq(RuntimeAgentTraceSpanEntity::getTraceId, traceId)
                .orderByAsc(RuntimeAgentTraceSpanEntity::getStartedAt)
                .orderByAsc(RuntimeAgentTraceSpanEntity::getId)));
    }

    private List<RuntimeGuardDecisionLogEntity> findGuardDecisions(String traceId) {
        return safeList(guardDecisionMapper.selectList(new LambdaQueryWrapper<RuntimeGuardDecisionLogEntity>()
                .eq(RuntimeGuardDecisionLogEntity::getTraceId, traceId)
                .orderByAsc(RuntimeGuardDecisionLogEntity::getCreatedAt)
                .orderByAsc(RuntimeGuardDecisionLogEntity::getId)
                .last("limit 200")));
    }

    private RuntimeRunOpsSummaryView buildSummary(String traceId,
                                                  List<RuntimeToolCallLogEntity> toolLogs,
                                                  List<RuntimeAgentTraceSpanEntity> spans,
                                                  List<RuntimeGuardDecisionLogEntity> guardDecisions,
                                                  Map<String, Object> metadata) {
        LocalDateTime startedAt = spans.stream().map(RuntimeAgentTraceSpanEntity::getStartedAt).filter(Objects::nonNull)
                .min(LocalDateTime::compareTo).orElseGet(() -> toolLogs.stream()
                        .map(RuntimeToolCallLogEntity::getCreateTime).filter(Objects::nonNull)
                        .min(LocalDateTime::compareTo).orElse(null));
        LocalDateTime endedAt = spans.stream().map(RuntimeAgentTraceSpanEntity::getEndedAt).filter(Objects::nonNull)
                .max(LocalDateTime::compareTo).orElseGet(() -> toolLogs.stream()
                        .map(RuntimeToolCallLogEntity::getCreateTime).filter(Objects::nonNull)
                        .max(LocalDateTime::compareTo).orElse(startedAt));
        int errorCount = (int) (spans.stream().filter(span -> "ERROR".equalsIgnoreCase(span.getStatus())).count()
                + toolLogs.stream().filter(log -> !Boolean.TRUE.equals(log.getSuccess())).count()
                + guardDecisions.stream().filter(decision -> "DENY".equalsIgnoreCase(decision.getDecision())).count());
        String fallbackReason = text(metadata.get("embeddedFallbackReason"));
        Optional<RuntimeAgentTraceSpanEntity> firstSpan = spans.stream().findFirst();
        Optional<RuntimeToolCallLogEntity> firstTool = toolLogs.stream().findFirst();
        String sourceType = text(metadata.get("sourceType"));
        String workflowId = firstText(text(metadata.get("workflowId")), text(metadata.get("resolvedWorkflowId")));
        String sourceId = firstText(text(metadata.get("sourceId")),
                StringUtils.hasText(sourceType) && sourceType.toUpperCase().startsWith("WORKFLOW") ? workflowId : null);
        return new RuntimeRunOpsSummaryView(
                traceId,
                errorCount == 0 ? "SUCCESS" : "ERROR",
                firstSpan.map(RuntimeAgentTraceSpanEntity::getAgentId).orElse(null),
                firstText(firstSpan.map(RuntimeAgentTraceSpanEntity::getAgentName).orElse(null),
                        firstTool.map(RuntimeToolCallLogEntity::getAgentName).orElse(null)),
                text(metadata.get("version")),
                numberAsLong(metadata.get("versionId")),
                firstText(text(metadata.get("runtimeType")), firstSpan.map(RuntimeAgentTraceSpanEntity::getRuntimeType).orElse(null)),
                text(metadata.get("runtimePlacement")),
                text(metadata.get("graphCode")),
                firstTool.map(RuntimeToolCallLogEntity::getSessionId).orElse(null),
                firstTool.map(RuntimeToolCallLogEntity::getUserId).orElse(null),
                firstText(text(metadata.get("intentType")), firstTool.map(RuntimeToolCallLogEntity::getIntentType).orElse(null)),
                startedAt,
                endedAt,
                spans.stream().map(RuntimeAgentTraceSpanEntity::getLatencyMs).filter(Objects::nonNull).mapToInt(Integer::intValue)
                        .max().orElse(millisBetween(startedAt, endedAt)),
                spans.stream().map(RuntimeAgentTraceSpanEntity::getTokenCost).filter(Objects::nonNull).mapToInt(Integer::intValue).sum()
                        + toolLogs.stream().map(RuntimeToolCallLogEntity::getTokenCost).filter(Objects::nonNull).mapToInt(Integer::intValue).sum(),
                spans.size(),
                toolLogs.size(),
                errorCount,
                StringUtils.hasText(fallbackReason),
                text(metadata.get("dispatchUrl")),
                fallbackReason,
                workflowId,
                text(metadata.get("workflowKeySlug")),
                text(metadata.get("workflowVersion")),
                numberAsLong(metadata.get("workflowVersionId")),
                text(metadata.get("entryAgentId")),
                text(metadata.get("entryAgentKeySlug")),
                sourceType,
                sourceId,
                metadata.isEmpty() ? null : metadata);
    }

    private RuntimeRunOpsSummaryView summaryFromToolLogs(String traceId, List<RuntimeToolCallLogEntity> traceLogs) {
        traceLogs.sort(Comparator.comparing(RuntimeToolCallLogEntity::getId));
        RuntimeToolCallLogEntity first = traceLogs.get(0);
        RuntimeToolCallLogEntity last = traceLogs.get(traceLogs.size() - 1);
        int errorCount = (int) traceLogs.stream().filter(log -> !Boolean.TRUE.equals(log.getSuccess())).count();
        return new RuntimeRunOpsSummaryView(
                traceId,
                errorCount == 0 ? "SUCCESS" : "ERROR",
                null,
                first.getAgentName(),
                null,
                null,
                null,
                null,
                null,
                first.getSessionId(),
                first.getUserId(),
                first.getIntentType(),
                first.getCreateTime(),
                last.getCreateTime(),
                millisBetween(first.getCreateTime(), last.getCreateTime()),
                traceLogs.stream().map(RuntimeToolCallLogEntity::getTokenCost).filter(Objects::nonNull).mapToInt(Integer::intValue).sum(),
                0,
                traceLogs.size(),
                errorCount,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());
    }

    private RuntimeRunOpsSpanView toSpanView(RuntimeAgentTraceSpanEntity span) {
        return new RuntimeRunOpsSpanView(
                span.getId(),
                span.getSpanId(),
                span.getParentSpanId(),
                span.getSpanType(),
                span.getRuntimeType(),
                span.getNodeId(),
                span.getToolName(),
                span.getStatus(),
                span.getInputSummary(),
                span.getOutputSummary(),
                parseMap(span.getMetadataJson()),
                span.getErrorCode(),
                span.getErrorMessage(),
                span.getLatencyMs(),
                span.getTokenCost(),
                span.getStartedAt(),
                span.getEndedAt());
    }

    private RuntimeRunOpsToolCallView toToolCallView(RuntimeToolCallLogEntity log) {
        return new RuntimeRunOpsToolCallView(
                log.getId(),
                log.getToolName(),
                log.getAgentName(),
                log.getSessionId(),
                log.getUserId(),
                log.getIntentType(),
                log.getProjectCode(),
                Boolean.TRUE.equals(log.getSuccess()),
                log.getArgsJson(),
                log.getResultSummary(),
                log.getErrorCode(),
                log.getElapsedMs(),
                log.getTokenCost(),
                log.getCreateTime());
    }

    private RuntimeRunOpsGuardDecisionView toGuardDecisionView(RuntimeGuardDecisionLogEntity decision) {
        return new RuntimeRunOpsGuardDecisionView(
                decision.getId(),
                decision.getDecisionType(),
                decision.getTargetKind(),
                decision.getTargetName(),
                decision.getDecision(),
                decision.getReason(),
                parseMap(decision.getMetadataJson()),
                decision.getCreatedAt());
    }

    private List<RuntimeRunOpsWorkflowPathItemView> workflowPath(List<RuntimeRunOpsSpanView> spans) {
        List<RuntimeRunOpsWorkflowPathItemView> path = new ArrayList<>();
        for (RuntimeRunOpsSpanView span : spans) {
            path.add(new RuntimeRunOpsWorkflowPathItemView(
                    span.parentSpanId(),
                    span.nodeId(),
                    null,
                    null,
                    span.status(),
                    span.status(),
                    null,
                    span.spanId(),
                    span.startedAt(),
                    span.endedAt()));
        }
        return path;
    }

    private List<String> repairHints(RuntimeRunOpsSummaryView summary, List<RuntimeRunOpsSpanView> spans) {
        if (summary == null || summary.errorCount() == null || summary.errorCount() == 0) {
            return List.of();
        }
        List<String> hints = new ArrayList<>();
        hints.add("检查失败 span、Tool 调用和 Guard 决策的错误码。");
        if (spans.stream().anyMatch(span -> StringUtils.hasText(span.errorCode()))) {
            hints.add("优先处理带 errorCode 的 Runtime span。");
        }
        return hints;
    }

    private List<RuntimeRunOpsDiffItemView> summaryDiffs(RuntimeRunOpsSummaryView baseline,
                                                         RuntimeRunOpsSummaryView candidate) {
        List<RuntimeRunOpsDiffItemView> diffs = new ArrayList<>();
        addDiff(diffs, "status", baseline.status(), candidate.status());
        addDiff(diffs, "version", baseline.version(), candidate.version());
        addDiff(diffs, "runtimePlacement", baseline.runtimePlacement(), candidate.runtimePlacement());
        addDiff(diffs, "latencyMs", baseline.latencyMs(), candidate.latencyMs());
        addDiff(diffs, "tokenCost", baseline.tokenCost(), candidate.tokenCost());
        addDiff(diffs, "errorCount", baseline.errorCount(), candidate.errorCount());
        addDiff(diffs, "fallback", baseline.fallback(), candidate.fallback());
        return diffs;
    }

    private List<RuntimeRunOpsSpanDiffView> spanDiffs(List<RuntimeRunOpsSpanView> baseline,
                                                      List<RuntimeRunOpsSpanView> candidate) {
        LinkedHashMap<String, RuntimeRunOpsSpanView> baselineMap = new LinkedHashMap<>();
        LinkedHashMap<String, RuntimeRunOpsSpanView> candidateMap = new LinkedHashMap<>();
        baseline.forEach(span -> baselineMap.put(spanKey(span), span));
        candidate.forEach(span -> candidateMap.put(spanKey(span), span));
        return unionKeys(baselineMap, candidateMap).stream()
                .map(key -> {
                    RuntimeRunOpsSpanView left = baselineMap.get(key);
                    RuntimeRunOpsSpanView right = candidateMap.get(key);
                    List<RuntimeRunOpsDiffItemView> diffs = new ArrayList<>();
                    if (left == null || right == null) {
                        diffs.add(new RuntimeRunOpsDiffItemView(
                                "presence", left == null ? null : "present", right == null ? null : "present", true));
                    } else {
                        addDiff(diffs, "status", left.status(), right.status());
                        addDiff(diffs, "latencyMs", left.latencyMs(), right.latencyMs());
                        addDiff(diffs, "errorCode", left.errorCode(), right.errorCode());
                        addDiff(diffs, "errorMessage", left.errorMessage(), right.errorMessage());
                        addDiff(diffs, "outputSummary", left.outputSummary(), right.outputSummary());
                    }
                    return new RuntimeRunOpsSpanDiffView(key, left, right, diffs, hasChanged(diffs));
                })
                .toList();
    }

    private List<RuntimeRunOpsToolDiffView> toolDiffs(List<RuntimeRunOpsToolCallView> baseline,
                                                      List<RuntimeRunOpsToolCallView> candidate) {
        LinkedHashMap<String, RuntimeRunOpsToolCallView> baselineMap = new LinkedHashMap<>();
        LinkedHashMap<String, RuntimeRunOpsToolCallView> candidateMap = new LinkedHashMap<>();
        baseline.forEach(tool -> baselineMap.put(toolKey(tool), tool));
        candidate.forEach(tool -> candidateMap.put(toolKey(tool), tool));
        return unionKeys(baselineMap, candidateMap).stream()
                .map(key -> {
                    RuntimeRunOpsToolCallView left = baselineMap.get(key);
                    RuntimeRunOpsToolCallView right = candidateMap.get(key);
                    List<RuntimeRunOpsDiffItemView> diffs = new ArrayList<>();
                    if (left == null || right == null) {
                        diffs.add(new RuntimeRunOpsDiffItemView(
                                "presence", left == null ? null : "present", right == null ? null : "present", true));
                    } else {
                        addDiff(diffs, "success", left.success(), right.success());
                        addDiff(diffs, "elapsedMs", left.elapsedMs(), right.elapsedMs());
                        addDiff(diffs, "errorCode", left.errorCode(), right.errorCode());
                        addDiff(diffs, "resultSummary", left.resultSummary(), right.resultSummary());
                    }
                    return new RuntimeRunOpsToolDiffView(key, left, right, diffs, hasChanged(diffs));
                })
                .toList();
    }

    private List<RuntimeRunOpsGuardDiffView> guardDiffs(List<RuntimeRunOpsGuardDecisionView> baseline,
                                                        List<RuntimeRunOpsGuardDecisionView> candidate) {
        LinkedHashMap<String, RuntimeRunOpsGuardDecisionView> baselineMap = new LinkedHashMap<>();
        LinkedHashMap<String, RuntimeRunOpsGuardDecisionView> candidateMap = new LinkedHashMap<>();
        baseline.forEach(guard -> baselineMap.put(guardKey(guard), guard));
        candidate.forEach(guard -> candidateMap.put(guardKey(guard), guard));
        return unionKeys(baselineMap, candidateMap).stream()
                .map(key -> {
                    RuntimeRunOpsGuardDecisionView left = baselineMap.get(key);
                    RuntimeRunOpsGuardDecisionView right = candidateMap.get(key);
                    List<RuntimeRunOpsDiffItemView> diffs = new ArrayList<>();
                    if (left == null || right == null) {
                        diffs.add(new RuntimeRunOpsDiffItemView(
                                "presence", left == null ? null : "present", right == null ? null : "present", true));
                    } else {
                        addDiff(diffs, "decision", left.decision(), right.decision());
                        addDiff(diffs, "reason", left.reason(), right.reason());
                    }
                    return new RuntimeRunOpsGuardDiffView(key, left, right, diffs, hasChanged(diffs));
                })
                .toList();
    }

    private List<RuntimeRunOpsFailureClusterView> failureClusters(List<RuntimeRunOpsDetailView> details) {
        LinkedHashMap<String, FailureClusterAccumulator> grouped = new LinkedHashMap<>();
        for (RuntimeRunOpsDetailView detail : details) {
            RuntimeRunOpsSummaryView summary = detail.summary();
            if (summary == null || "SUCCESS".equalsIgnoreCase(summary.status())) {
                continue;
            }
            RuntimeRunOpsSpanView failedSpan = detail.spans().stream()
                    .filter(span -> !"SUCCESS".equalsIgnoreCase(span.status()))
                    .findFirst()
                    .orElse(null);
            RuntimeRunOpsToolCallView failedTool = detail.toolCalls().stream()
                    .filter(tool -> !tool.success())
                    .findFirst()
                    .orElse(null);
            RuntimeRunOpsGuardDecisionView deniedGuard = detail.guardDecisions().stream()
                    .filter(guard -> "DENY".equalsIgnoreCase(guard.decision()))
                    .findFirst()
                    .orElse(null);
            String errorType = firstPresent(
                    failedSpan == null ? null : failedSpan.errorCode(),
                    failedTool == null ? null : failedTool.errorCode(),
                    deniedGuard == null ? null : deniedGuard.decisionType(),
                    summary.fallback() ? "HYBRID_FALLBACK" : null,
                    "RUN_ERROR");
            String nodeId = failedSpan == null ? null : failedSpan.nodeId();
            String toolName = firstPresent(
                    failedSpan == null ? null : failedSpan.toolName(),
                    failedTool == null ? null : failedTool.toolName());
            String key = String.join("|",
                    normalizeKey(groupIdentityKey(summary)),
                    normalizeKey(groupVersionKey(summary)),
                    normalizeKey(errorType),
                    normalizeKey(nodeId),
                    normalizeKey(toolName));
            FailureClusterAccumulator accumulator = grouped.computeIfAbsent(key,
                    ignored -> FailureClusterAccumulator.fromSummary(summary, errorType, nodeId, toolName));
            accumulator.add(detail, firstPresent(
                    failedSpan == null ? null : failedSpan.errorMessage(),
                    failedTool == null ? null : failedTool.resultSummary(),
                    deniedGuard == null ? null : deniedGuard.reason(),
                    summary.fallbackReason()));
        }
        return grouped.values().stream()
                .map(FailureClusterAccumulator::toCluster)
                .sorted(Comparator
                        .comparing(RuntimeRunOpsFailureClusterView::count).reversed()
                        .thenComparing(RuntimeRunOpsFailureClusterView::lastSeenAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(20)
                .toList();
    }

    private List<RuntimeRunOpsVersionComparisonView> versionComparisons(List<RuntimeRunOpsDetailView> details) {
        LinkedHashMap<String, List<RuntimeRunOpsDetailView>> grouped = new LinkedHashMap<>();
        for (RuntimeRunOpsDetailView detail : details) {
            RuntimeRunOpsSummaryView summary = detail.summary();
            if (summary == null) {
                continue;
            }
            String key = String.join("|", normalizeKey(groupIdentityKey(summary)), normalizeKey(groupVersionKey(summary)));
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(detail);
        }
        return grouped.values().stream()
                .map(rows -> {
                    RuntimeRunOpsSummaryView sample = rows.get(0).summary();
                    int total = rows.size();
                    int failures = (int) rows.stream()
                            .filter(detail -> !"SUCCESS".equalsIgnoreCase(detail.summary().status()))
                            .count();
                    int fallbackCount = (int) rows.stream().filter(detail -> detail.summary().fallback()).count();
                    int toolErrorCount = rows.stream()
                            .mapToInt(detail -> (int) detail.toolCalls().stream().filter(tool -> !tool.success()).count())
                            .sum();
                    int guardDenyCount = rows.stream()
                            .mapToInt(detail -> (int) detail.guardDecisions().stream()
                                    .filter(guard -> "DENY".equalsIgnoreCase(guard.decision()))
                                    .count())
                            .sum();
                    List<Integer> latencies = rows.stream()
                            .map(RuntimeRunOpsDetailView::summary)
                            .map(RuntimeRunOpsSummaryView::latencyMs)
                            .filter(Objects::nonNull)
                            .sorted()
                            .toList();
                    int avgLatency = total == 0 ? 0 : (int) Math.round(rows.stream()
                            .map(RuntimeRunOpsDetailView::summary)
                            .map(RuntimeRunOpsSummaryView::latencyMs)
                            .filter(Objects::nonNull)
                            .mapToInt(Integer::intValue)
                            .average()
                            .orElse(0D));
                    int avgToken = total == 0 ? 0 : (int) Math.round(rows.stream()
                            .map(RuntimeRunOpsDetailView::summary)
                            .map(RuntimeRunOpsSummaryView::tokenCost)
                            .filter(Objects::nonNull)
                            .mapToInt(Integer::intValue)
                            .average()
                            .orElse(0D));
                    RuntimeRunOpsSummaryView latest = rows.stream()
                            .map(RuntimeRunOpsDetailView::summary)
                            .max(Comparator.comparing(RuntimeRunOpsSummaryView::startedAt,
                                    Comparator.nullsLast(LocalDateTime::compareTo)))
                            .orElse(sample);
                    return new RuntimeRunOpsVersionComparisonView(
                            sample.agentId(),
                            sample.agentName(),
                            sample.version(),
                            sample.versionId(),
                            sample.runtimeType(),
                            sample.runtimePlacement(),
                            total,
                            total - failures,
                            failures,
                            total == 0 ? 0D : (double) (total - failures) / total,
                            avgLatency,
                            percentile(latencies, 95),
                            avgToken,
                            fallbackCount,
                            toolErrorCount,
                            guardDenyCount,
                            latest.traceId(),
                            latest.startedAt(),
                            sample.workflowId(),
                            sample.workflowKeySlug(),
                            sample.workflowVersion(),
                            sample.workflowVersionId(),
                            sample.sourceType(),
                            sample.sourceId());
                })
                .sorted(Comparator
                        .comparing(RuntimeRunOpsVersionComparisonView::failureCount).reversed()
                        .thenComparing(RuntimeRunOpsVersionComparisonView::runCount, Comparator.reverseOrder()))
                .toList();
    }

    private Map<String, Object> mergedMetadata(List<RuntimeAgentTraceSpanEntity> spans) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (RuntimeAgentTraceSpanEntity span : spans) {
            merged.putAll(parseMap(span.getMetadataJson()));
        }
        return merged;
    }

    private Map<String, Object> parseMap(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return Map.of("raw", raw);
        }
    }

    private int millisBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        long millis = Duration.between(start, end).toMillis();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, millis));
    }

    private Long numberAsLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String firstPresent(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String spanKey(RuntimeRunOpsSpanView span) {
        return normalizeKey(firstPresent(span.nodeId(), span.toolName(), span.spanType(), span.spanId()));
    }

    private String toolKey(RuntimeRunOpsToolCallView tool) {
        return normalizeKey(tool.toolName());
    }

    private String guardKey(RuntimeRunOpsGuardDecisionView guard) {
        return String.join("|",
                normalizeKey(guard.decisionType()),
                normalizeKey(guard.targetKind()),
                normalizeKey(guard.targetName()));
    }

    private String groupIdentityKey(RuntimeRunOpsSummaryView summary) {
        if (isWorkflowSummary(summary)) {
            return firstPresent(summary.workflowId(), summary.sourceId(), summary.agentId(), summary.agentName());
        }
        return firstPresent(summary.agentId(), summary.agentName());
    }

    private String groupVersionKey(RuntimeRunOpsSummaryView summary) {
        return firstPresent(
                summary.versionId() == null ? null : String.valueOf(summary.versionId()),
                summary.version(),
                summary.runtimeType(),
                summary.runtimePlacement());
    }

    private boolean isWorkflowSummary(RuntimeRunOpsSummaryView summary) {
        if (summary == null) {
            return false;
        }
        return isWorkflowSourceType(summary.sourceType()) || StringUtils.hasText(summary.workflowId());
    }

    private boolean isWorkflowSourceType(String sourceType) {
        return StringUtils.hasText(sourceType) && sourceType.toUpperCase().startsWith("WORKFLOW");
    }

    private String normalizeKey(String value) {
        return StringUtils.hasText(value) ? value.trim() : "-";
    }

    private <T> List<String> unionKeys(Map<String, T> baseline, Map<String, T> candidate) {
        LinkedHashMap<String, Boolean> keys = new LinkedHashMap<>();
        baseline.keySet().forEach(key -> keys.put(key, true));
        candidate.keySet().forEach(key -> keys.putIfAbsent(key, true));
        return new ArrayList<>(keys.keySet());
    }

    private void addDiff(List<RuntimeRunOpsDiffItemView> diffs, String field, Object baseline, Object candidate) {
        boolean changed = !Objects.equals(baseline, candidate);
        diffs.add(new RuntimeRunOpsDiffItemView(field, baseline, candidate, changed));
    }

    private boolean hasChanged(List<RuntimeRunOpsDiffItemView> diffs) {
        return diffs.stream().anyMatch(RuntimeRunOpsDiffItemView::changed);
    }

    private int percentile(List<Integer> values, int percentile) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil((percentile / 100D) * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private <T> List<T> safeList(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    private static class FailureClusterAccumulator {
        private final String agentId;
        private final String agentName;
        private final String version;
        private final Long versionId;
        private final String runtimeType;
        private final String runtimePlacement;
        private final String errorType;
        private final String nodeId;
        private final String toolName;
        private final String workflowId;
        private final String workflowKeySlug;
        private final String workflowVersion;
        private final Long workflowVersionId;
        private final String sourceType;
        private final String sourceId;
        private final List<String> traceIds = new ArrayList<>();
        private final List<String> repairHints = new ArrayList<>();
        private int count;
        private int fallbackCount;
        private int totalLatencyMs;
        private LocalDateTime firstSeenAt;
        private LocalDateTime lastSeenAt;
        private String sampleTraceId;
        private String sampleError;

        private FailureClusterAccumulator(RuntimeRunOpsSummaryView summary,
                                          String errorType,
                                          String nodeId,
                                          String toolName) {
            this.agentId = summary.agentId();
            this.agentName = summary.agentName();
            this.version = summary.version();
            this.versionId = summary.versionId();
            this.runtimeType = summary.runtimeType();
            this.runtimePlacement = summary.runtimePlacement();
            this.errorType = errorType;
            this.nodeId = nodeId;
            this.toolName = toolName;
            this.workflowId = summary.workflowId();
            this.workflowKeySlug = summary.workflowKeySlug();
            this.workflowVersion = summary.workflowVersion();
            this.workflowVersionId = summary.workflowVersionId();
            this.sourceType = summary.sourceType();
            this.sourceId = summary.sourceId();
        }

        private static FailureClusterAccumulator fromSummary(RuntimeRunOpsSummaryView summary,
                                                             String errorType,
                                                             String nodeId,
                                                             String toolName) {
            return new FailureClusterAccumulator(summary, errorType, nodeId, toolName);
        }

        private void add(RuntimeRunOpsDetailView detail, String error) {
            RuntimeRunOpsSummaryView summary = detail.summary();
            count++;
            if (summary.fallback()) {
                fallbackCount++;
            }
            if (summary.latencyMs() != null) {
                totalLatencyMs += summary.latencyMs();
            }
            if (StringUtils.hasText(summary.traceId())) {
                traceIds.add(summary.traceId());
                if (sampleTraceId == null) {
                    sampleTraceId = summary.traceId();
                }
            }
            if (sampleError == null && StringUtils.hasText(error)) {
                sampleError = error;
            }
            repairHints.addAll(detail.repairHints());
            LocalDateTime startedAt = summary.startedAt();
            if (startedAt != null) {
                if (firstSeenAt == null || startedAt.isBefore(firstSeenAt)) {
                    firstSeenAt = startedAt;
                }
                if (lastSeenAt == null || startedAt.isAfter(lastSeenAt)) {
                    lastSeenAt = startedAt;
                }
            }
        }

        private RuntimeRunOpsFailureClusterView toCluster() {
            return new RuntimeRunOpsFailureClusterView(
                    agentId,
                    agentName,
                    version,
                    versionId,
                    runtimeType,
                    runtimePlacement,
                    errorType,
                    nodeId,
                    toolName,
                    count,
                    fallbackCount,
                    count == 0 ? 0 : Math.round((float) totalLatencyMs / count),
                    firstSeenAt,
                    lastSeenAt,
                    sampleTraceId,
                    traceIds,
                    sampleError,
                    repairHints.stream().distinct().toList(),
                    workflowId,
                    workflowKeySlug,
                    workflowVersion,
                    workflowVersionId,
                    sourceType,
                    sourceId);
        }
    }
}
