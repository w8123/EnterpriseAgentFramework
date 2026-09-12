package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsComparisonView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDiagnosticsView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDiffItemView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsExecutionPathItemView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsGuardDecisionView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsGuardDiffView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSnapshotView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSpanDiffView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSpanView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsToolCallView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsToolDiffView;
import com.enterprise.ai.runtime.trace.RuntimeTraceRecordQuery;
import com.enterprise.ai.runtime.trace.RuntimeTraceRecords;
import com.enterprise.ai.runtime.trace.RuntimeTraceSummaryView;
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

import static com.enterprise.ai.runtime.runops.RuntimeRunOpsFailurePolicy.isRunFailureStatus;
import static com.enterprise.ai.runtime.runops.RuntimeRunOpsFailurePolicy.isSpanFailureStatus;

@Service
@RequiredArgsConstructor
public class RuntimeRunOpsQueryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeRunMapper runMapper;
    private final RuntimeTraceRecordQuery traceRecordQuery;
    private final RuntimeGuardDecisionLogMapper guardDecisionMapper;
    private final ObjectMapper objectMapper;
    private final RuntimeRunOpsDiagnosticsCalculator diagnosticsCalculator = new RuntimeRunOpsDiagnosticsCalculator();

    public RuntimeRunOpsDetailView detail(String traceId) {
        String normalizedTraceId = requiredText(traceId, "traceId 不能为空");
        RuntimeRunEntity run = runMapper.selectOne(new LambdaQueryWrapper<RuntimeRunEntity>()
                .eq(RuntimeRunEntity::getTraceId, normalizedTraceId)
                .last("limit 1"));
        if (run == null) {
            throw new IllegalArgumentException("RunOps 运行记录不存在: " + normalizedTraceId);
        }

        RuntimeTraceRecords records = traceRecordQuery.findRecords(normalizedTraceId);
        List<RuntimeGuardDecisionLogEntity> guardDecisions = findGuardDecisions(normalizedTraceId);
        return detailView(run, records, guardDecisions);
    }

    private RuntimeRunOpsDetailView detailView(RuntimeRunEntity run, RuntimeTraceRecords records,
                                               List<RuntimeGuardDecisionLogEntity> guardDecisions) {
        RuntimeRunOpsSummaryView summary = toSummary(run);
        List<RuntimeRunOpsSpanView> spanViews = records.spans().stream().map(this::toSpanView).toList();
        var outcomes = new RuntimeRunOpsToolOutcomeResolver(objectMapper, records.spans());
        List<RuntimeRunOpsToolCallView> toolViews = records.toolCalls().stream()
                .map(tool -> toToolCallView(tool, outcomes.resolve(tool))).toList();
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
        return findRecentRuns(projectCode, status, runType, entryType, agentId, userId, keyword, limit, days)
                .stream().map(this::toSummary).toList();
    }

    private List<RuntimeRunEntity> findRecentRuns(String projectCode, String status, String runType,
                                                 String entryType, String agentId, String userId,
                                                 String keyword, int limit, int days) {
        int safeLimit = safeLimit(limit);
        int safeDays = safeDays(days);
        String canonicalStatus = StringUtils.hasText(status)
                ? RuntimeRunStatus.parse(status).name() : null;
        LambdaQueryWrapper<RuntimeRunEntity> wrapper = new LambdaQueryWrapper<RuntimeRunEntity>()
                .isNotNull(RuntimeRunEntity::getTraceId)
                .ge(RuntimeRunEntity::getStartedAt, LocalDateTime.now().minusDays(safeDays))
                .eq(StringUtils.hasText(projectCode), RuntimeRunEntity::getProjectCode, trim(projectCode))
                .eq(StringUtils.hasText(canonicalStatus), RuntimeRunEntity::getStatus, canonicalStatus)
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
        return safeList(runMapper.selectList(wrapper));
    }

    /** 既有 Trace 列表的分页与字段投影，由运行主记录所属模块维护。 */
    public List<RuntimeTraceSummaryView> listRecentTraces(String userId, int limit, int days) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeDays = Math.max(1, Math.min(days, 30));
        LambdaQueryWrapper<RuntimeRunEntity> wrapper = new LambdaQueryWrapper<RuntimeRunEntity>()
                .ge(RuntimeRunEntity::getStartedAt, LocalDateTime.now().minusDays(safeDays))
                .orderByDesc(RuntimeRunEntity::getStartedAt)
                .orderByDesc(RuntimeRunEntity::getId)
                .last("limit " + safeLimit);
        if (StringUtils.hasText(userId)) {
            wrapper.eq(RuntimeRunEntity::getUserId, userId.trim());
        }
        return runMapper.selectList(wrapper).stream()
                .map(this::toTraceSummary)
                .toList();
    }

    private RuntimeTraceSummaryView toTraceSummary(RuntimeRunEntity run) {
        return new RuntimeTraceSummaryView(
                run.getTraceId(),
                run.getSessionId(),
                run.getUserId(),
                StringUtils.hasText(run.getAgentName()) ? run.getAgentName() : run.getWorkflowName(),
                run.getEntryType(),
                run.getToolCallCount() == null ? 0 : run.getToolCallCount(),
                RuntimeRunStatus.parse(run.getStatus()) == RuntimeRunStatus.COMPLETED ? 1L : 0L,
                run.getStartedAt(),
                run.getEndedAt());
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
        List<RuntimeRunEntity> runs = findRecentRuns(
                projectCode, status, runType, entryType, agentId, userId, keyword, limit, days);
        if (runs.isEmpty()) return diagnosticsCalculator.calculate(List.of());
        List<String> traceIds = runs.stream().map(RuntimeRunEntity::getTraceId).distinct().toList();
        Map<String, RuntimeTraceRecords> records = traceRecordQuery.findRecordsByTraceIds(traceIds);
        Map<String, List<RuntimeGuardDecisionLogEntity>> guards = new HashMap<>();
        for (RuntimeGuardDecisionLogEntity guard : guardDecisionMapper.selectForTraceIds(traceIds)) {
            guards.computeIfAbsent(guard.getTraceId(), ignored -> new ArrayList<>()).add(guard);
        }
        List<RuntimeRunOpsDetailView> details = runs.stream()
                .map(run -> detailView(run, records.get(run.getTraceId()),
                        guards.getOrDefault(run.getTraceId(), List.of())))
                .toList();
        return diagnosticsCalculator.calculate(details);
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
                RuntimeRunStatus.parse(run.getStatus()).name(),
                resolvedSuspensionReason(run),
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

    private String resolvedSuspensionReason(RuntimeRunEntity run) {
        if (StringUtils.hasText(run.getSuspensionReason())) {
            return run.getSuspensionReason().trim().toUpperCase();
        }
        return null;
    }

    private RuntimeRunOpsSpanView toSpanView(RuntimeTraceRecords.Span span) {
        return new RuntimeRunOpsSpanView(
                span.id(),
                span.spanId(),
                span.parentSpanId(),
                span.spanType(),
                span.runtimeType(),
                span.nodeId(),
                span.toolName(),
                span.status(),
                span.inputSummary(),
                span.outputSummary(),
                parseMap(span.metadataJson()),
                span.errorCode(),
                span.errorMessage(),
                safeInt(span.latencyMs()),
                safeInt(span.tokenCost()),
                span.startedAt(),
                span.endedAt());
    }

    private RuntimeRunOpsToolCallView toToolCallView(RuntimeTraceRecords.ToolCall tool,
                                                    RuntimeRunOpsToolOutcomeResolver.Outcome outcome) {
        return new RuntimeRunOpsToolCallView(
                tool.id(),
                tool.toolName(),
                tool.agentName(),
                tool.sessionId(),
                tool.userId(),
                tool.intentType(),
                tool.projectCode(),
                Boolean.TRUE.equals(tool.success()),
                tool.argsJson(),
                tool.resultSummary(),
                tool.errorCode(),
                safeInt(tool.elapsedMs()),
                safeInt(tool.tokenCost()),
                tool.createTime(), outcome.status(), outcome.sourceSpanId());
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
        boolean failed = isRunFailureStatus(summary.status())
                || spans.stream().anyMatch(span -> isSpanFailureStatus(span.status()))
                || tools.stream().anyMatch(RuntimeRunOpsFailurePolicy::isToolFailure)
                || guards.stream().anyMatch(RuntimeRunOpsFailurePolicy::isDenied);
        if (!failed) {
            if (tools.stream().anyMatch(tool -> "UNKNOWN".equals(tool.status())
                    || "COMPLETED".equals(summary.status()) && "WAITING_USER".equals(tool.status()))) {
                return List.of("部分调用的最终状态尚未确认，请核对关联追踪。");
            }
            return List.of();
        }
        List<String> hints = new ArrayList<>();
        if (StringUtils.hasText(summary.errorCode())) {
            hints.add("先按根运行错误码 " + summary.errorCode() + " 定位失败阶段。");
        }
        if (spans.stream().anyMatch(span -> isSpanFailureStatus(span.status()))) {
            hints.add("检查 executionPath 中失败的 Supervisor、Workflow Tool 或 Workflow Node span。");
        }
        if (tools.stream().anyMatch(RuntimeRunOpsFailurePolicy::isToolFailure)) {
            hints.add("检查失败 Tool 调用的参数、ACL、目标服务和错误码。");
        }
        if (guards.stream().anyMatch(RuntimeRunOpsFailurePolicy::isDenied)) {
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
        addDiff(diffs, "suspensionReason", baseline.suspensionReason(), candidate.suspensionReason());
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
                addDiff(diffs, "status", baselineTool.status(), candidateTool.status());
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

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }


}
