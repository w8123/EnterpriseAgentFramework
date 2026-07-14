package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsComparisonView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDiagnosticsView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDiffItemView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsExecutionPathItemView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsFailureClusterView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsGuardDecisionView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsGuardDiffView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSnapshotView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSpanDiffView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSpanView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsToolCallView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsToolDiffView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsVersionComparisonView;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RuntimeRunOpsQueryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeRunMapper runMapper;
    private final RuntimeTraceSpanMapper spanMapper;
    private final RuntimeToolCallLogMapper toolLogMapper;
    private final RuntimeGuardDecisionLogMapper guardDecisionMapper;
    private final ObjectMapper objectMapper;

    public RuntimeRunOpsDetailView detail(String traceId) {
        String normalizedTraceId = requiredText(traceId, "traceId 不能为空");
        RuntimeRunEntity run = runMapper.selectOne(new LambdaQueryWrapper<RuntimeRunEntity>()
                .eq(RuntimeRunEntity::getTraceId, normalizedTraceId)
                .last("limit 1"));
        if (run == null) {
            throw new IllegalArgumentException("RunOps 运行记录不存在: " + normalizedTraceId);
        }

        List<RuntimeTraceSpanEntity> spans = findSpans(normalizedTraceId);
        List<RuntimeToolCallLogEntity> toolCalls = findToolCalls(normalizedTraceId);
        List<RuntimeGuardDecisionLogEntity> guardDecisions = findGuardDecisions(normalizedTraceId);
        RuntimeRunOpsSummaryView summary = toSummary(run);
        List<RuntimeRunOpsSpanView> spanViews = spans.stream().map(this::toSpanView).toList();
        List<RuntimeRunOpsToolCallView> toolViews = toolCalls.stream().map(this::toToolCallView).toList();
        List<RuntimeRunOpsGuardDecisionView> guardViews = guardDecisions.stream()
                .map(this::toGuardDecisionView)
                .toList();
        return new RuntimeRunOpsDetailView(
                summary,
                spanViews,
                toolViews,
                guardViews,
                snapshot(run),
                executionPath(spanViews),
                repairHints(summary, spanViews, toolViews, guardViews));
    }

    public List<RuntimeRunOpsSummaryView> recent(String projectCode,
                                                 String status,
                                                 String runType,
                                                 String entryType,
                                                 String agentId,
                                                 String userId,
                                                 int limit,
                                                 int days) {
        return recent(projectCode, status, runType, entryType, agentId, userId, null, limit, days);
    }

    public List<RuntimeRunOpsSummaryView> recent(String projectCode,
                                                 String status,
                                                 String runType,
                                                 String entryType,
                                                 String agentId,
                                                 String userId,
                                                 String keyword,
                                                 int limit,
                                                 int days) {
        int safeLimit = safeLimit(limit);
        int safeDays = safeDays(days);
        LambdaQueryWrapper<RuntimeRunEntity> wrapper = new LambdaQueryWrapper<RuntimeRunEntity>()
                .isNotNull(RuntimeRunEntity::getTraceId)
                .ge(RuntimeRunEntity::getStartedAt, LocalDateTime.now().minusDays(safeDays))
                .eq(StringUtils.hasText(projectCode), RuntimeRunEntity::getProjectCode, trim(projectCode))
                .eq(StringUtils.hasText(status), RuntimeRunEntity::getStatus, upper(status))
                .eq(StringUtils.hasText(runType), RuntimeRunEntity::getRunType, upper(runType))
                .eq(StringUtils.hasText(entryType), RuntimeRunEntity::getEntryType, upper(entryType))
                .eq(StringUtils.hasText(agentId), RuntimeRunEntity::getAgentId, trim(agentId))
                .eq(StringUtils.hasText(userId), RuntimeRunEntity::getUserId, trim(userId))
                .and(StringUtils.hasText(keyword), search -> search
                        .like(RuntimeRunEntity::getTraceId, trim(keyword))
                        .or().like(RuntimeRunEntity::getAgentId, trim(keyword))
                        .or().like(RuntimeRunEntity::getAgentKeySlug, trim(keyword))
                        .or().like(RuntimeRunEntity::getAgentName, trim(keyword))
                        .or().like(RuntimeRunEntity::getWorkflowId, trim(keyword))
                        .or().like(RuntimeRunEntity::getWorkflowKeySlug, trim(keyword))
                        .or().like(RuntimeRunEntity::getWorkflowName, trim(keyword))
                        .or().like(RuntimeRunEntity::getErrorCode, trim(keyword))
                        .or().like(RuntimeRunEntity::getErrorMessage, trim(keyword))
                        .or().like(RuntimeRunEntity::getUserId, trim(keyword)))
                .orderByDesc(RuntimeRunEntity::getStartedAt)
                .orderByDesc(RuntimeRunEntity::getId)
                .last("limit " + safeLimit);
        return safeList(runMapper.selectList(wrapper)).stream().map(this::toSummary).toList();
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

    public RuntimeRunOpsDiagnosticsView diagnostics(String projectCode,
                                                     String status,
                                                     String runType,
                                                     String entryType,
                                                    String agentId,
                                                    String userId,
                                                    int limit,
                                                    int days) {
        return diagnostics(projectCode, status, runType, entryType, agentId, userId, null, limit, days);
    }

    public RuntimeRunOpsDiagnosticsView diagnostics(String projectCode,
                                                     String status,
                                                     String runType,
                                                     String entryType,
                                                     String agentId,
                                                     String userId,
                                                     String keyword,
                                                     int limit,
                                                     int days) {
        List<RuntimeRunOpsDetailView> details = recent(
                projectCode, status, runType, entryType, agentId, userId, keyword, limit, days).stream()
                .map(RuntimeRunOpsSummaryView::traceId)
                .map(this::detail)
                .toList();
        return new RuntimeRunOpsDiagnosticsView(failureClusters(details), versionComparisons(details));
    }

    private List<RuntimeTraceSpanEntity> findSpans(String traceId) {
        return safeList(spanMapper.selectList(new LambdaQueryWrapper<RuntimeTraceSpanEntity>()
                .eq(RuntimeTraceSpanEntity::getTraceId, traceId)
                .orderByAsc(RuntimeTraceSpanEntity::getStartedAt)
                .orderByAsc(RuntimeTraceSpanEntity::getId)));
    }

    private List<RuntimeToolCallLogEntity> findToolCalls(String traceId) {
        return safeList(toolLogMapper.selectList(new LambdaQueryWrapper<RuntimeToolCallLogEntity>()
                .eq(RuntimeToolCallLogEntity::getTraceId, traceId)
                .orderByAsc(RuntimeToolCallLogEntity::getCreateTime)
                .orderByAsc(RuntimeToolCallLogEntity::getId)));
    }

    private List<RuntimeGuardDecisionLogEntity> findGuardDecisions(String traceId) {
        return safeList(guardDecisionMapper.selectList(new LambdaQueryWrapper<RuntimeGuardDecisionLogEntity>()
                .eq(RuntimeGuardDecisionLogEntity::getTraceId, traceId)
                .orderByAsc(RuntimeGuardDecisionLogEntity::getCreatedAt)
                .orderByAsc(RuntimeGuardDecisionLogEntity::getId)
                .last("limit 500")));
    }

    private RuntimeRunOpsSummaryView toSummary(RuntimeRunEntity run) {
        return new RuntimeRunOpsSummaryView(
                run.getTraceId(),
                run.getRunType(),
                run.getEntryType(),
                run.getStatus(),
                run.getProjectCode(),
                run.getTenantId(),
                run.getSessionId(),
                run.getUserId(),
                run.getAgentId(),
                run.getAgentKeySlug(),
                run.getAgentName(),
                run.getAgentConfigVersionId(),
                run.getAgentConfigVersion(),
                run.getWorkflowId(),
                run.getWorkflowKeySlug(),
                run.getWorkflowName(),
                run.getWorkflowVersionId(),
                run.getWorkflowVersion(),
                run.getRuntimeType(),
                run.getInputSummary(),
                run.getOutputSummary(),
                run.getErrorCode(),
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getEndedAt(),
                safeInt(run.getLatencyMs()),
                safeInt(run.getTokenCost()),
                safeInt(run.getPlanCount()),
                safeInt(run.getReplanCount()),
                safeInt(run.getWorkflowCallCount()),
                safeInt(run.getToolCallCount()),
                safeInt(run.getGuardDenyCount()),
                safeInt(run.getApprovalCount()),
                run.getReplayOfTraceId(),
                parseMap(run.getMetadataJson()));
    }

    private RuntimeRunOpsSpanView toSpanView(RuntimeTraceSpanEntity span) {
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
                safeInt(span.getLatencyMs()),
                safeInt(span.getTokenCost()),
                span.getStartedAt(),
                span.getEndedAt());
    }

    private RuntimeRunOpsToolCallView toToolCallView(RuntimeToolCallLogEntity tool) {
        return new RuntimeRunOpsToolCallView(
                tool.getId(),
                tool.getToolName(),
                tool.getAgentName(),
                tool.getSessionId(),
                tool.getUserId(),
                tool.getIntentType(),
                tool.getProjectCode(),
                Boolean.TRUE.equals(tool.getSuccess()),
                tool.getArgsJson(),
                tool.getResultSummary(),
                tool.getErrorCode(),
                safeInt(tool.getElapsedMs()),
                safeInt(tool.getTokenCost()),
                tool.getCreateTime());
    }

    private RuntimeRunOpsGuardDecisionView toGuardDecisionView(RuntimeGuardDecisionLogEntity guard) {
        return new RuntimeRunOpsGuardDecisionView(
                guard.getId(),
                guard.getDecisionType(),
                guard.getTargetKind(),
                guard.getTargetName(),
                guard.getDecision(),
                guard.getReason(),
                parseMap(guard.getMetadataJson()),
                guard.getCreatedAt());
    }

    private RuntimeRunOpsSnapshotView snapshot(RuntimeRunEntity run) {
        if (!StringUtils.hasText(run.getSnapshotJson())) {
            return null;
        }
        return new RuntimeRunOpsSnapshotView(
                run.getRunType(),
                run.getAgentId(),
                run.getAgentKeySlug(),
                run.getAgentName(),
                run.getAgentConfigVersionId(),
                run.getAgentConfigVersion(),
                run.getWorkflowId(),
                run.getWorkflowKeySlug(),
                run.getWorkflowName(),
                run.getWorkflowVersionId(),
                run.getWorkflowVersion(),
                run.getRuntimeType(),
                parseMap(run.getSnapshotJson()),
                run.getSnapshotJson());
    }

    private List<RuntimeRunOpsExecutionPathItemView> executionPath(List<RuntimeRunOpsSpanView> spans) {
        Map<String, RuntimeRunOpsSpanView> bySpanId = new HashMap<>();
        for (RuntimeRunOpsSpanView span : spans) {
            if (StringUtils.hasText(span.spanId())) {
                bySpanId.put(span.spanId(), span);
            }
        }
        Map<String, Integer> depthCache = new HashMap<>();
        List<RuntimeRunOpsExecutionPathItemView> path = new ArrayList<>();
        for (RuntimeRunOpsSpanView span : spans) {
            Map<String, Object> metadata = span.metadata() == null ? Map.of() : span.metadata();
            Map<String, Object> step = mapValue(metadata.get("step"));
            RuntimeRunOpsSpanView parent = StringUtils.hasText(span.parentSpanId())
                    ? bySpanId.get(span.parentSpanId())
                    : null;
            path.add(new RuntimeRunOpsExecutionPathItemView(
                    span.spanId(),
                    span.parentSpanId(),
                    spanDepth(span, bySpanId, depthCache, new HashSet<>()),
                    span.spanType(),
                    spanLabel(span),
                    span.status(),
                    span.nodeId(),
                    span.toolName(),
                    span.runtimeType(),
                    firstText(
                            text(metadata.get("fromNodeId")), text(metadata.get("source")),
                            text(step.get("fromNodeId")), text(step.get("source")), text(step.get("from")),
                            parent == null ? null : parent.nodeId()),
                    firstText(
                            text(metadata.get("toNodeId")), text(metadata.get("target")),
                            text(step.get("toNodeId")), text(step.get("target")), text(step.get("to")),
                            span.nodeId()),
                    firstText(text(metadata.get("condition")), text(step.get("condition"))),
                    firstText(text(metadata.get("route")), text(step.get("route"))),
                    firstText(text(metadata.get("workflowStatus")), text(step.get("workflowStatus")), span.status()),
                    firstText(text(metadata.get("interactionId")), text(step.get("interactionId"))),
                    span.startedAt(),
                    span.endedAt()));
        }
        return path;
    }

    private int spanDepth(RuntimeRunOpsSpanView span,
                          Map<String, RuntimeRunOpsSpanView> bySpanId,
                          Map<String, Integer> cache,
                          Set<String> visiting) {
        if (span == null || !StringUtils.hasText(span.spanId())) {
            return 0;
        }
        Integer cached = cache.get(span.spanId());
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(span.spanId())) {
            return 0;
        }
        RuntimeRunOpsSpanView parent = StringUtils.hasText(span.parentSpanId())
                ? bySpanId.get(span.parentSpanId())
                : null;
        int depth = parent == null ? 0 : 1 + spanDepth(parent, bySpanId, cache, visiting);
        visiting.remove(span.spanId());
        cache.put(span.spanId(), depth);
        return depth;
    }

    private String spanLabel(RuntimeRunOpsSpanView span) {
        Map<String, Object> metadata = span.metadata() == null ? Map.of() : span.metadata();
        String type = upper(span.spanType());
        if ("SUPERVISOR".equals(type)) {
            return firstText(text(metadata.get("agentName")), "Supervisor");
        }
        if ("PLAN".equals(type)) {
            return "Plan";
        }
        if ("REPLAN".equals(type)) {
            return "Replan";
        }
        if ("WORKFLOW_TOOL".equals(type)) {
            return firstText(text(metadata.get("workflowName")), text(metadata.get("workflowKeySlug")),
                    span.toolName(), "Workflow Tool");
        }
        if (type != null && type.startsWith("WORKFLOW")) {
            return firstText(span.nodeId(), span.toolName(), text(metadata.get("workflowName")), span.spanType());
        }
        return firstText(span.nodeId(), span.toolName(), span.spanType(), span.spanId());
    }

    private List<String> repairHints(RuntimeRunOpsSummaryView summary,
                                     List<RuntimeRunOpsSpanView> spans,
                                     List<RuntimeRunOpsToolCallView> tools,
                                     List<RuntimeRunOpsGuardDecisionView> guards) {
        boolean failed = isFailureStatus(summary.status())
                || spans.stream().anyMatch(span -> isFailureStatus(span.status()))
                || tools.stream().anyMatch(tool -> !tool.success())
                || guards.stream().anyMatch(this::isDenied);
        if (!failed) {
            return List.of();
        }
        List<String> hints = new ArrayList<>();
        if (StringUtils.hasText(summary.errorCode())) {
            hints.add("先按根运行错误码 " + summary.errorCode() + " 定位失败阶段。");
        }
        if (spans.stream().anyMatch(span -> isFailureStatus(span.status()))) {
            hints.add("检查 executionPath 中失败的 Supervisor、Workflow Tool 或 Workflow Node span。");
        }
        if (tools.stream().anyMatch(tool -> !tool.success())) {
            hints.add("检查失败 Tool 调用的参数、ACL、目标服务和错误码。");
        }
        if (guards.stream().anyMatch(this::isDenied)) {
            hints.add("检查 Guard 拒绝原因以及调用身份和策略配置。");
        }
        return hints;
    }

    private List<RuntimeRunOpsDiffItemView> summaryDiffs(RuntimeRunOpsSummaryView baseline,
                                                         RuntimeRunOpsSummaryView candidate) {
        List<RuntimeRunOpsDiffItemView> diffs = new ArrayList<>();
        addDiff(diffs, "runType", baseline.runType(), candidate.runType());
        addDiff(diffs, "entryType", baseline.entryType(), candidate.entryType());
        addDiff(diffs, "status", baseline.status(), candidate.status());
        addDiff(diffs, "agentId", baseline.agentId(), candidate.agentId());
        addDiff(diffs, "agentConfigVersionId", baseline.agentConfigVersionId(), candidate.agentConfigVersionId());
        addDiff(diffs, "workflowId", baseline.workflowId(), candidate.workflowId());
        addDiff(diffs, "workflowVersionId", baseline.workflowVersionId(), candidate.workflowVersionId());
        addDiff(diffs, "runtimeType", baseline.runtimeType(), candidate.runtimeType());
        addDiff(diffs, "latencyMs", baseline.latencyMs(), candidate.latencyMs());
        addDiff(diffs, "tokenCost", baseline.tokenCost(), candidate.tokenCost());
        addDiff(diffs, "planCount", baseline.planCount(), candidate.planCount());
        addDiff(diffs, "replanCount", baseline.replanCount(), candidate.replanCount());
        addDiff(diffs, "workflowCallCount", baseline.workflowCallCount(), candidate.workflowCallCount());
        addDiff(diffs, "toolCallCount", baseline.toolCallCount(), candidate.toolCallCount());
        addDiff(diffs, "guardDenyCount", baseline.guardDenyCount(), candidate.guardDenyCount());
        addDiff(diffs, "approvalCount", baseline.approvalCount(), candidate.approvalCount());
        addDiff(diffs, "errorCode", baseline.errorCode(), candidate.errorCode());
        return diffs;
    }

    private List<RuntimeRunOpsSpanDiffView> spanDiffs(List<RuntimeRunOpsSpanView> baseline,
                                                      List<RuntimeRunOpsSpanView> candidate) {
        Map<String, RuntimeRunOpsSpanView> left = indexSpans(baseline);
        Map<String, RuntimeRunOpsSpanView> right = indexSpans(candidate);
        LinkedHashSet<String> keys = unionKeys(left, right);
        List<RuntimeRunOpsSpanDiffView> result = new ArrayList<>();
        for (String key : keys) {
            RuntimeRunOpsSpanView baselineSpan = left.get(key);
            RuntimeRunOpsSpanView candidateSpan = right.get(key);
            List<RuntimeRunOpsDiffItemView> diffs = new ArrayList<>();
            if (baselineSpan == null || candidateSpan == null) {
                diffs.add(new RuntimeRunOpsDiffItemView("presence", baselineSpan != null, candidateSpan != null, true));
            } else {
                addDiff(diffs, "status", baselineSpan.status(), candidateSpan.status());
                addDiff(diffs, "latencyMs", baselineSpan.latencyMs(), candidateSpan.latencyMs());
                addDiff(diffs, "tokenCost", baselineSpan.tokenCost(), candidateSpan.tokenCost());
                addDiff(diffs, "errorCode", baselineSpan.errorCode(), candidateSpan.errorCode());
                addDiff(diffs, "outputSummary", baselineSpan.outputSummary(), candidateSpan.outputSummary());
            }
            result.add(new RuntimeRunOpsSpanDiffView(
                    key, baselineSpan, candidateSpan, diffs, hasChanged(diffs)));
        }
        return result;
    }

    private List<RuntimeRunOpsToolDiffView> toolDiffs(List<RuntimeRunOpsToolCallView> baseline,
                                                      List<RuntimeRunOpsToolCallView> candidate) {
        Map<String, RuntimeRunOpsToolCallView> left = indexTools(baseline);
        Map<String, RuntimeRunOpsToolCallView> right = indexTools(candidate);
        LinkedHashSet<String> keys = unionKeys(left, right);
        List<RuntimeRunOpsToolDiffView> result = new ArrayList<>();
        for (String key : keys) {
            RuntimeRunOpsToolCallView baselineTool = left.get(key);
            RuntimeRunOpsToolCallView candidateTool = right.get(key);
            List<RuntimeRunOpsDiffItemView> diffs = new ArrayList<>();
            if (baselineTool == null || candidateTool == null) {
                diffs.add(new RuntimeRunOpsDiffItemView("presence", baselineTool != null, candidateTool != null, true));
            } else {
                addDiff(diffs, "success", baselineTool.success(), candidateTool.success());
                addDiff(diffs, "errorCode", baselineTool.errorCode(), candidateTool.errorCode());
                addDiff(diffs, "elapsedMs", baselineTool.elapsedMs(), candidateTool.elapsedMs());
                addDiff(diffs, "resultSummary", baselineTool.resultSummary(), candidateTool.resultSummary());
            }
            result.add(new RuntimeRunOpsToolDiffView(
                    key, baselineTool, candidateTool, diffs, hasChanged(diffs)));
        }
        return result;
    }

    private List<RuntimeRunOpsGuardDiffView> guardDiffs(List<RuntimeRunOpsGuardDecisionView> baseline,
                                                        List<RuntimeRunOpsGuardDecisionView> candidate) {
        Map<String, RuntimeRunOpsGuardDecisionView> left = indexGuards(baseline);
        Map<String, RuntimeRunOpsGuardDecisionView> right = indexGuards(candidate);
        LinkedHashSet<String> keys = unionKeys(left, right);
        List<RuntimeRunOpsGuardDiffView> result = new ArrayList<>();
        for (String key : keys) {
            RuntimeRunOpsGuardDecisionView baselineGuard = left.get(key);
            RuntimeRunOpsGuardDecisionView candidateGuard = right.get(key);
            List<RuntimeRunOpsDiffItemView> diffs = new ArrayList<>();
            if (baselineGuard == null || candidateGuard == null) {
                diffs.add(new RuntimeRunOpsDiffItemView("presence", baselineGuard != null, candidateGuard != null, true));
            } else {
                addDiff(diffs, "decision", baselineGuard.decision(), candidateGuard.decision());
                addDiff(diffs, "reason", baselineGuard.reason(), candidateGuard.reason());
            }
            result.add(new RuntimeRunOpsGuardDiffView(
                    key, baselineGuard, candidateGuard, diffs, hasChanged(diffs)));
        }
        return result;
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
                .filter(span -> isFailureStatus(span.status()))
                .findFirst()
                .orElse(null);
        RuntimeRunOpsToolCallView failedTool = detail.toolCalls().stream()
                .filter(tool -> !tool.success())
                .findFirst()
                .orElse(null);
        RuntimeRunOpsGuardDecisionView denied = detail.guardDecisions().stream()
                .filter(this::isDenied)
                .findFirst()
                .orElse(null);
        if (!isFailureStatus(summary.status()) && failedSpan == null && failedTool == null && denied == null) {
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

    private Map<String, RuntimeRunOpsSpanView> indexSpans(List<RuntimeRunOpsSpanView> spans) {
        LinkedHashMap<String, RuntimeRunOpsSpanView> indexed = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (RuntimeRunOpsSpanView span : safeList(spans)) {
            String base = String.join("|",
                    nullSafe(span.spanType()), nullSafe(span.nodeId()), nullSafe(span.toolName()));
            int ordinal = counts.merge(base, 1, Integer::sum);
            indexed.put(base + "#" + ordinal, span);
        }
        return indexed;
    }

    private Map<String, RuntimeRunOpsToolCallView> indexTools(List<RuntimeRunOpsToolCallView> tools) {
        LinkedHashMap<String, RuntimeRunOpsToolCallView> indexed = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (RuntimeRunOpsToolCallView tool : safeList(tools)) {
            String base = nullSafe(tool.toolName());
            int ordinal = counts.merge(base, 1, Integer::sum);
            indexed.put(base + "#" + ordinal, tool);
        }
        return indexed;
    }

    private Map<String, RuntimeRunOpsGuardDecisionView> indexGuards(List<RuntimeRunOpsGuardDecisionView> guards) {
        LinkedHashMap<String, RuntimeRunOpsGuardDecisionView> indexed = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (RuntimeRunOpsGuardDecisionView guard : safeList(guards)) {
            String base = String.join("|", nullSafe(guard.decisionType()),
                    nullSafe(guard.targetKind()), nullSafe(guard.targetName()));
            int ordinal = counts.merge(base, 1, Integer::sum);
            indexed.put(base + "#" + ordinal, guard);
        }
        return indexed;
    }

    private <T> LinkedHashSet<String> unionKeys(Map<String, T> baseline, Map<String, T> candidate) {
        LinkedHashSet<String> keys = new LinkedHashSet<>(baseline.keySet());
        keys.addAll(candidate.keySet());
        return keys;
    }

    private void addDiff(List<RuntimeRunOpsDiffItemView> diffs,
                         String field,
                         Object baseline,
                         Object candidate) {
        diffs.add(new RuntimeRunOpsDiffItemView(field, baseline, candidate,
                !Objects.equals(baseline, candidate)));
    }

    private boolean hasChanged(List<RuntimeRunOpsDiffItemView> diffs) {
        return diffs.stream().anyMatch(RuntimeRunOpsDiffItemView::changed);
    }

    private boolean isFailureStatus(String status) {
        return "FAILED".equalsIgnoreCase(status)
                || "ERROR".equalsIgnoreCase(status)
                || "TIMEOUT".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status);
    }

    private boolean isDenied(RuntimeRunOpsGuardDecisionView guard) {
        return guard != null && "DENY".equalsIgnoreCase(guard.decision());
    }

    private Map<String, Object> parseMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private int safeLimit(int limit) {
        return Math.max(1, Math.min(limit <= 0 ? 50 : limit, 100));
    }

    private int safeDays(int days) {
        return Math.max(1, Math.min(days <= 0 ? 7 : days, 90));
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String requiredText(String value, String error) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(error);
        }
        return value.trim();
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String upper(String value) {
        String normalized = trim(value);
        return normalized == null ? null : normalized.toUpperCase();
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

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
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
                    .filter(summary -> "SUCCESS".equalsIgnoreCase(summary.status()))
                    .count();
            int failureCount = (int) rows.stream()
                    .map(RuntimeRunOpsDetailView::summary)
                    .filter(summary -> isFailureStatus(summary.status()))
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
                    .filter(tool -> !tool.success()).count()).sum();
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
