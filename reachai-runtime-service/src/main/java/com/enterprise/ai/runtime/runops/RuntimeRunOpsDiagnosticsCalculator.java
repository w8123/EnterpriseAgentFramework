package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDiagnosticsView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsFailureClusterView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsGuardDecisionView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSpanView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsToolCallView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsVersionComparisonView;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.enterprise.ai.runtime.runops.RuntimeRunOpsFailurePolicy.isRunFailureStatus;
import static com.enterprise.ai.runtime.runops.RuntimeRunOpsFailurePolicy.isSpanFailureStatus;

/** 根据已有运行证据计算失败聚类和版本统计；不查询数据库，也不修改输入视图。 */
final class RuntimeRunOpsDiagnosticsCalculator {

    RuntimeRunOpsDiagnosticsView calculate(List<RuntimeRunOpsDetailView> details) {
        return new RuntimeRunOpsDiagnosticsView(failureClusters(details), versionComparisons(details));
    }

    private List<RuntimeRunOpsFailureClusterView> failureClusters(List<RuntimeRunOpsDetailView> details) {
        LinkedHashMap<String, FailureAccumulator> grouped = new LinkedHashMap<>();
        for (RuntimeRunOpsDetailView detail : details) {
            FailureEvidence evidence = failureEvidence(detail);
            if (evidence == null) {
                continue;
            }
            VersionIdentity identity = failureIdentity(detail, evidence.span());
            String key = identity.key() + "|" + nullSafe(evidence.errorCode()) + "|"
                    + nullSafe(evidence.spanType()) + "|" + nullSafe(evidence.nodeId()) + "|"
                    + nullSafe(evidence.toolName());
            grouped.computeIfAbsent(key, ignored -> new FailureAccumulator(identity, evidence))
                    .add(detail.summary(), evidence);
        }
        return grouped.values().stream()
                .map(FailureAccumulator::toView)
                .sorted(Comparator.comparing(RuntimeRunOpsFailureClusterView::count).reversed()
                        .thenComparing(RuntimeRunOpsFailureClusterView::lastSeenAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private FailureEvidence failureEvidence(RuntimeRunOpsDetailView detail) {
        RuntimeRunOpsSummaryView summary = detail.summary();
        RuntimeRunOpsSpanView failedSpan = detail.spans().stream()
                .filter(span -> isSpanFailureStatus(span.status()))
                .findFirst()
                .orElse(null);
        RuntimeRunOpsToolCallView failedTool = detail.toolCalls().stream()
                .filter(RuntimeRunOpsFailurePolicy::isToolFailure)
                .findFirst()
                .orElse(null);
        RuntimeRunOpsGuardDecisionView denied = detail.guardDecisions().stream()
                .filter(RuntimeRunOpsFailurePolicy::isDenied)
                .findFirst()
                .orElse(null);
        if (!isRunFailureStatus(summary.status()) && failedSpan == null && failedTool == null && denied == null) {
            return null;
        }
        String code = firstText(
                summary.errorCode(),
                failedSpan == null ? null : failedSpan.errorCode(),
                failedTool == null ? null : failedTool.errorCode(),
                denied == null ? null : "GUARD_DENIED",
                summary.status());
        String message = firstText(
                summary.errorMessage(),
                failedSpan == null ? null : failedSpan.errorMessage(),
                failedTool == null ? null : failedTool.resultSummary(),
                denied == null ? null : denied.reason());
        return new FailureEvidence(
                code,
                message,
                failedSpan == null ? null : failedSpan.spanType(),
                failedSpan == null ? null : failedSpan.nodeId(),
                failedSpan != null ? failedSpan.toolName() : failedTool == null ? null : failedTool.toolName(),
                failedSpan);
    }

    private VersionIdentity failureIdentity(RuntimeRunOpsDetailView detail, RuntimeRunOpsSpanView failedSpan) {
        if (failedSpan != null && failedSpan.metadata() != null
                && ("WORKFLOW_TOOL".equalsIgnoreCase(failedSpan.spanType())
                || (failedSpan.spanType() != null && failedSpan.spanType().toUpperCase().startsWith("WORKFLOW")))) {
            VersionIdentity workflow = workflowIdentity(failedSpan.metadata(), detail.summary());
            if (workflow != null) {
                return workflow;
            }
        }
        return primaryIdentity(detail.summary());
    }

    private List<RuntimeRunOpsVersionComparisonView> versionComparisons(List<RuntimeRunOpsDetailView> details) {
        LinkedHashMap<String, VersionAccumulator> grouped = new LinkedHashMap<>();
        for (RuntimeRunOpsDetailView detail : details) {
            for (VersionIdentity identity : versionIdentities(detail)) {
                grouped.computeIfAbsent(identity.key(), ignored -> new VersionAccumulator(identity)).add(detail);
            }
        }
        return grouped.values().stream()
                .map(VersionAccumulator::toView)
                .sorted(Comparator.comparing(RuntimeRunOpsVersionComparisonView::failureCount).reversed()
                        .thenComparing(RuntimeRunOpsVersionComparisonView::runCount, Comparator.reverseOrder()))
                .toList();
    }

    private List<VersionIdentity> versionIdentities(RuntimeRunOpsDetailView detail) {
        RuntimeRunOpsSummaryView summary = detail.summary();
        LinkedHashMap<String, VersionIdentity> identities = new LinkedHashMap<>();
        if (StringUtils.hasText(summary.agentId()) || summary.agentConfigVersionId() != null) {
            VersionIdentity agent = agentIdentity(summary);
            identities.put(agent.key(), agent);
        }
        if (StringUtils.hasText(summary.workflowId()) || summary.workflowVersionId() != null) {
            VersionIdentity workflow = rootWorkflowIdentity(summary);
            identities.put(workflow.key(), workflow);
        }
        for (RuntimeRunOpsSpanView span : detail.spans()) {
            if (!"WORKFLOW_TOOL".equalsIgnoreCase(span.spanType())) {
                continue;
            }
            VersionIdentity workflow = workflowIdentity(span.metadata(), summary);
            if (workflow != null) {
                identities.put(workflow.key(), workflow);
            }
        }
        if (identities.isEmpty()) {
            VersionIdentity primary = primaryIdentity(summary);
            identities.put(primary.key(), primary);
        }
        return new ArrayList<>(identities.values());
    }

    private VersionIdentity primaryIdentity(RuntimeRunOpsSummaryView summary) {
        if (StringUtils.hasText(summary.workflowId()) && !StringUtils.hasText(summary.agentId())) {
            return rootWorkflowIdentity(summary);
        }
        return agentIdentity(summary);
    }

    private VersionIdentity agentIdentity(RuntimeRunOpsSummaryView summary) {
        return new VersionIdentity(
                "AGENT",
                summary.agentId(),
                summary.agentName(),
                summary.agentConfigVersionId(),
                summary.agentConfigVersion(),
                null,
                null,
                null,
                null,
                summary.runtimeType());
    }

    private VersionIdentity rootWorkflowIdentity(RuntimeRunOpsSummaryView summary) {
        return new VersionIdentity(
                "WORKFLOW",
                null,
                null,
                null,
                null,
                summary.workflowId(),
                summary.workflowName(),
                summary.workflowVersionId(),
                summary.workflowVersion(),
                summary.runtimeType());
    }

    private VersionIdentity workflowIdentity(Map<String, Object> metadata, RuntimeRunOpsSummaryView summary) {
        if (metadata == null) {
            return null;
        }
        String workflowId = text(metadata.get("workflowId"));
        Long workflowVersionId = numberAsLong(metadata.get("workflowVersionId"));
        if (!StringUtils.hasText(workflowId) && workflowVersionId == null) {
            return null;
        }
        return new VersionIdentity(
                "WORKFLOW",
                null,
                null,
                null,
                null,
                workflowId,
                firstText(text(metadata.get("workflowName")), text(metadata.get("workflowKeySlug"))),
                workflowVersionId,
                text(metadata.get("workflowVersion")),
                firstText(text(metadata.get("runtimeType")), summary.runtimeType()));
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }


    private String text(Object value) {
        return value == null ? null : trim(String.valueOf(value));
    }


    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }


    private Long numberAsLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (!StringUtils.hasText(text(value))) {
            return null;
        }
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }


    private String nullSafe(String value) {
        return value == null ? "" : value;
    }


    private record FailureEvidence(String errorCode,
                                   String errorMessage,
                                   String spanType,
                                   String nodeId,
                                   String toolName,
                                   RuntimeRunOpsSpanView span) {
    }

    private record VersionIdentity(String versionType,
                                   String agentId,
                                   String agentName,
                                   Long agentConfigVersionId,
                                   Integer agentConfigVersion,
                                   String workflowId,
                                   String workflowName,
                                   Long workflowVersionId,
                                   String workflowVersion,
                                   String runtimeType) {
        private String key() {
            return String.join("|",
                    nullSafeValue(versionType),
                    nullSafeValue(agentId),
                    String.valueOf(agentConfigVersionId),
                    nullSafeValue(workflowId),
                    String.valueOf(workflowVersionId),
                    nullSafeValue(runtimeType));
        }

        private static String nullSafeValue(Object value) {
            return value == null ? "" : String.valueOf(value);
        }
    }

    private final class FailureAccumulator {
        private final VersionIdentity identity;
        private final String errorCode;
        private final String errorMessage;
        private final String spanType;
        private final String nodeId;
        private final String toolName;
        private final List<String> traceIds = new ArrayList<>();
        private int latencyTotal;
        private int latencyCount;
        private LocalDateTime firstSeenAt;
        private LocalDateTime lastSeenAt;

        private FailureAccumulator(VersionIdentity identity, FailureEvidence evidence) {
            this.identity = identity;
            this.errorCode = evidence.errorCode();
            this.errorMessage = evidence.errorMessage();
            this.spanType = evidence.spanType();
            this.nodeId = evidence.nodeId();
            this.toolName = evidence.toolName();
        }

        private void add(RuntimeRunOpsSummaryView summary, FailureEvidence evidence) {
            traceIds.add(summary.traceId());
            if (summary.latencyMs() != null) {
                latencyTotal += summary.latencyMs();
                latencyCount++;
            }
            if (summary.startedAt() != null
                    && (firstSeenAt == null || summary.startedAt().isBefore(firstSeenAt))) {
                firstSeenAt = summary.startedAt();
            }
            if (summary.startedAt() != null
                    && (lastSeenAt == null || summary.startedAt().isAfter(lastSeenAt))) {
                lastSeenAt = summary.startedAt();
            }
        }

        private RuntimeRunOpsFailureClusterView toView() {
            return new RuntimeRunOpsFailureClusterView(
                    identity.versionType(),
                    identity.agentId(),
                    identity.agentName(),
                    identity.agentConfigVersionId(),
                    identity.agentConfigVersion(),
                    identity.workflowId(),
                    identity.workflowName(),
                    identity.workflowVersionId(),
                    identity.workflowVersion(),
                    identity.runtimeType(),
                    errorCode,
                    errorMessage,
                    spanType,
                    nodeId,
                    toolName,
                    traceIds.size(),
                    latencyCount == 0 ? 0 : latencyTotal / latencyCount,
                    firstSeenAt,
                    lastSeenAt,
                    traceIds.isEmpty() ? null : traceIds.get(0),
                    List.copyOf(traceIds),
                    List.of("打开样例运行，沿 executionPath 定位首个失败节点。"));
        }
    }

    private final class VersionAccumulator {
        private final VersionIdentity identity;
        private final List<RuntimeRunOpsDetailView> rows = new ArrayList<>();

        private VersionAccumulator(VersionIdentity identity) {
            this.identity = identity;
        }

        private void add(RuntimeRunOpsDetailView detail) {
            rows.add(detail);
        }

        private RuntimeRunOpsVersionComparisonView toView() {
            int runCount = rows.size();
            int successCount = (int) rows.stream()
                    .map(RuntimeRunOpsDetailView::summary)
                    .filter(summary -> RuntimeRunStatus.parse(summary.status()) == RuntimeRunStatus.COMPLETED)
                    .count();
            int failureCount = (int) rows.stream()
                    .map(RuntimeRunOpsDetailView::summary)
                    .filter(summary -> isRunFailureStatus(summary.status()))
                    .count();
            List<Integer> latencies = rows.stream()
                    .map(RuntimeRunOpsDetailView::summary)
                    .map(RuntimeRunOpsSummaryView::latencyMs)
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
            int avgLatency = average(latencies);
            int p95Latency = percentile95(latencies);
            int avgTokenCost = average(rows.stream()
                    .map(RuntimeRunOpsDetailView::summary)
                    .map(RuntimeRunOpsSummaryView::tokenCost)
                    .filter(Objects::nonNull)
                    .toList());
            int workflowCallCount = rows.stream()
                    .map(RuntimeRunOpsDetailView::summary)
                    .map(RuntimeRunOpsSummaryView::workflowCallCount)
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .sum();
            int toolErrorCount = rows.stream().mapToInt(row -> (int) row.toolCalls().stream()
                    .filter(RuntimeRunOpsFailurePolicy::isToolFailure).count()).sum();
            int guardDenyCount = rows.stream()
                    .map(RuntimeRunOpsDetailView::summary)
                    .map(RuntimeRunOpsSummaryView::guardDenyCount)
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .sum();
            int replanCount = rows.stream()
                    .map(RuntimeRunOpsDetailView::summary)
                    .map(RuntimeRunOpsSummaryView::replanCount)
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .sum();
            RuntimeRunOpsSummaryView latest = rows.stream()
                    .map(RuntimeRunOpsDetailView::summary)
                    .max(Comparator.comparing(RuntimeRunOpsSummaryView::startedAt,
                            Comparator.nullsFirst(LocalDateTime::compareTo)))
                    .orElse(null);
            return new RuntimeRunOpsVersionComparisonView(
                    identity.versionType(),
                    identity.agentId(),
                    identity.agentName(),
                    identity.agentConfigVersionId(),
                    identity.agentConfigVersion(),
                    identity.workflowId(),
                    identity.workflowName(),
                    identity.workflowVersionId(),
                    identity.workflowVersion(),
                    identity.runtimeType(),
                    runCount,
                    successCount,
                    failureCount,
                    runCount == 0 ? 0D : successCount * 1D / runCount,
                    avgLatency,
                    p95Latency,
                    avgTokenCost,
                    workflowCallCount,
                    toolErrorCount,
                    guardDenyCount,
                    replanCount,
                    latest == null ? null : latest.traceId(),
                    latest == null ? null : latest.startedAt());
        }
    }

    private int average(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        return (int) Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    private int percentile95(List<Integer> sortedValues) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0;
        }
        int index = Math.max(0, (int) Math.ceil(sortedValues.size() * 0.95D) - 1);
        return sortedValues.get(index);
    }
}
