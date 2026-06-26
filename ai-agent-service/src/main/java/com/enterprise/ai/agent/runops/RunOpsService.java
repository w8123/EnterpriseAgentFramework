package com.enterprise.ai.agent.runops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.enterprise.ai.agent.platform.control.governance.GuardDecisionLogEntity;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeGraphAdapter;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import com.enterprise.ai.agent.workflow.WorkflowVersionEntity;
import com.enterprise.ai.agent.workflow.WorkflowVersionMapper;
import com.enterprise.ai.agent.platform.control.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.tool.log.ToolCallLogEntity;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.trace.AgentTraceSpanEntity;
import com.enterprise.ai.agent.trace.AgentTraceSpanMapper;
import com.enterprise.ai.agent.trace.AgentTraceSpanService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RunOpsService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ToolCallLogService toolCallLogService;
    private final AgentTraceSpanService traceSpanService;
    private final AgentTraceSpanMapper traceSpanMapper;
    private final GuardDecisionLogService guardDecisionLogService;
    private final AgentEntryService agentEntryService;
    private final WorkflowRuntimeGraphAdapter workflowRuntimeGraphAdapter;
    private final AgentRouter agentRouter;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final ObjectMapper objectMapper;

    public RunDetail detail(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            throw new IllegalArgumentException("traceId 不能为空");
        }
        String normalizedTraceId = traceId.trim();
        List<ToolCallLogEntity> toolLogs = toolCallLogService.getTraceLogs(normalizedTraceId);
        List<AgentTraceSpanEntity> spans = traceSpanService.listByTraceId(normalizedTraceId);
        List<GuardDecisionLogEntity> decisions = guardDecisionLogService.search(
                new GuardDecisionLogService.SearchQuery(normalizedTraceId, null, null, null, null, null, null, 200));
        if (toolLogs.isEmpty() && spans.isEmpty() && decisions.isEmpty()) {
            throw new IllegalArgumentException("RunOps 运行记录不存在: " + normalizedTraceId);
        }

        Map<String, Object> metadata = mergedMetadata(spans);
        AgentRuntimeProfile currentAgent = resolveCurrentProfile(spans, toolLogs);
        AgentRuntimeProfile effectiveAgent = currentAgent;

        RunSummary summary = buildSummary(normalizedTraceId, toolLogs, spans, decisions, metadata, effectiveAgent);
        List<SpanView> spanViews = spans.stream()
                .sorted(Comparator
                        .comparing(AgentTraceSpanEntity::getStartedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                        .thenComparing(AgentTraceSpanEntity::getId, Comparator.nullsLast(Long::compareTo)))
                .map(span -> new SpanView(
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
                        span.getEndedAt()))
                .toList();
        List<ToolCallView> toolViews = toolLogs.stream()
                .map(log -> new ToolCallView(
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
                        log.getCreateTime()))
                .toList();
        List<GuardDecisionView> guardViews = decisions.stream()
                .map(decision -> new GuardDecisionView(
                        decision.getId(),
                        decision.getDecisionType(),
                        decision.getTargetKind(),
                        decision.getTargetName(),
                        decision.getDecision(),
                        decision.getReason(),
                        parseMap(decision.getMetadataJson()),
                        decision.getCreatedAt()))
                .toList();

        RunSnapshot runSnapshot = effectiveAgent == null ? null : new RunSnapshot(
                effectiveAgent.getId(),
                effectiveAgent.getName(),
                effectiveAgent.getKeySlug(),
                effectiveAgent.getRuntimeType(),
                effectiveAgent.getRuntimePlacement(),
                effectiveAgent.getRuntimeConfig(),
                null,
                null);
        return new RunDetail(summary, spanViews, toolViews, guardViews, runSnapshot,
                workflowPath(spanViews, null),
                repairHints(summary, spanViews, guardViews));
    }

    public List<RunSummary> recent(String userId, int limit, int days) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<ToolCallLogService.TraceSummary> toolTraces = toolCallLogService.listRecentTraces(userId, safeLimit, days);
        LinkedHashMap<String, RunSummary> rows = new LinkedHashMap<>();
        for (ToolCallLogService.TraceSummary trace : toolTraces) {
            rows.put(trace.traceId(), createRunSummary(
                    trace.traceId(),
                    trace.successCount() == trace.callCount() ? "SUCCESS" : "ERROR",
                    null,
                    trace.agentName(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    trace.sessionId(),
                    trace.userId(),
                    trace.intentType(),
                    trace.startedAt(),
                    trace.endedAt(),
                    millisBetween(trace.startedAt(), trace.endedAt()),
                    0,
                    trace.callCount(),
                    0,
                    trace.callCount() - (int) trace.successCount(),
                    false,
                    null,
                    null,
                    Map.of()));
        }

        if (rows.size() < safeLimit) {
            List<AgentTraceSpanEntity> spans = traceSpanMapper.selectList(new LambdaQueryWrapper<AgentTraceSpanEntity>()
                    .isNotNull(AgentTraceSpanEntity::getTraceId)
                    .ge(AgentTraceSpanEntity::getCreatedAt, LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 30))))
                    .orderByDesc(AgentTraceSpanEntity::getId)
                    .last("limit " + Math.max(safeLimit * 20, 200)));
            for (AgentTraceSpanEntity span : spans) {
                if (rows.size() >= safeLimit) {
                    break;
                }
                if (!StringUtils.hasText(span.getTraceId()) || rows.containsKey(span.getTraceId())) {
                    continue;
                }
                Map<String, Object> metadata = parseMap(span.getMetadataJson());
                rows.put(span.getTraceId(), createRunSummary(
                        span.getTraceId(),
                        "SUCCESS".equalsIgnoreCase(span.getStatus()) ? "SUCCESS" : "ERROR",
                        span.getAgentId(),
                        span.getAgentName(),
                        asText(metadata.get("version")),
                        asLong(metadata.get("versionId")),
                        firstText(span.getRuntimeType(), asText(metadata.get("runtimeType"))),
                        asText(metadata.get("runtimePlacement")),
                        asText(metadata.get("graphCode")),
                        null,
                        null,
                        asText(metadata.get("intentType")),
                        firstNonNull(span.getStartedAt(), span.getCreatedAt()),
                        firstNonNull(span.getEndedAt(), span.getCreatedAt()),
                        span.getLatencyMs() == null ? 0 : span.getLatencyMs(),
                        span.getTokenCost() == null ? 0 : span.getTokenCost(),
                        1,
                        0,
                        "SUCCESS".equalsIgnoreCase(span.getStatus()) ? 0 : 1,
                        false,
                        asText(metadata.get("dispatchUrl")),
                        asText(metadata.get("embeddedFallbackReason")),
                        metadata));
            }
        }
        return new ArrayList<>(rows.values());
    }

    public RunDiagnostics diagnostics(String userId, int limit, int days) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<RunSummary> summaries = recent(userId, safeLimit, days);
        List<RunDetail> details = summaries.stream()
                .map(summary -> {
                    try {
                        return detail(summary.traceId());
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
        return new RunDiagnostics(failureClusters(details), versionComparisons(details));
    }

    public ReplayResult replay(String traceId, ReplayRequest request) {
        if (!StringUtils.hasText(traceId)) {
            throw new IllegalArgumentException("traceId 不能为空");
        }
        String normalizedTraceId = traceId.trim();
        List<ToolCallLogEntity> toolLogs = toolCallLogService.getTraceLogs(normalizedTraceId);
        List<AgentTraceSpanEntity> spans = traceSpanService.listByTraceId(normalizedTraceId);
        if (toolLogs.isEmpty() && spans.isEmpty()) {
            throw new IllegalArgumentException("RunOps 运行记录不存在: " + normalizedTraceId);
        }

        Map<String, Object> metadata = mergedMetadata(spans);
        boolean useSnapshot = request == null || request.useSnapshot() == null || request.useSnapshot();

        String message = firstText(request == null ? null : request.messageOverride(), replayMessage(spans, toolLogs));
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("无法从 trace 中还原运行输入，请提供 messageOverride");
        }
        String sessionId = firstText(request == null ? null : request.sessionId(),
                "replay-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        String userId = firstText(request == null ? null : request.userId(), firstTool(toolLogs).map(ToolCallLogEntity::getUserId).orElse(null), "runops-replay");
        List<String> roles = request == null || request.roles() == null ? List.of() : request.roles();

        Map<String, Object> replayMetadata = buildReplayMetadata(normalizedTraceId, metadata, useSnapshot);
        enrichWorkflowReplayMetadata(replayMetadata, metadata);

        if (isWorkflowMetadata(metadata)) {
            WorkflowReplayResolution workflowReplay = resolveWorkflowReplay(metadata, useSnapshot);
            if (workflowReplay != null && workflowReplay.graph() != null) {
                GraphSpec graphSpec = workflowReplay.graph().graphSpec();
                GraphRuntimeContext runtimeContext = workflowReplay.graph().runtimeContext();
                enrichWorkflowReplayMetadata(replayMetadata, runtimeContext);
                replayMetadata.put("replayExecutionPath", "GRAPH_SPEC");
                if (workflowReplay.fallbackReason() != null) {
                    replayMetadata.put("replayFallbackReason", workflowReplay.fallbackReason());
                }
                AgentResult result = agentRouter.executeByGraphSpec(
                        graphSpec, runtimeContext, sessionId, userId, message, roles, replayMetadata);
                return buildReplayResult(
                        normalizedTraceId,
                        sessionId,
                        userId,
                        message,
                        result,
                        replayMetadata,
                        workflowReplay,
                        null,
                        "GRAPH_SPEC",
                        workflowReplay.fallbackReason());
            }
            throw new IllegalArgumentException(firstText(
                    workflowReplay == null ? null : workflowReplay.fallbackReason(),
                    "无法从 trace metadata 重放 Workflow"));
        }

        AgentRuntimeProfile profile = resolveCurrentProfile(spans, toolLogs);
        if (profile == null) {
            throw new IllegalArgumentException("无法解析重放使用的 Agent profile: " + normalizedTraceId);
        }
        replayMetadata.putIfAbsent("replayExecutionPath", "AGENT_PROFILE");

        AgentResult result = agentRouter.executeByProfile(profile, sessionId, userId, message, roles, replayMetadata);
        return buildReplayResult(
                normalizedTraceId,
                sessionId,
                userId,
                message,
                result,
                replayMetadata,
                null,
                profile,
                "AGENT_PROFILE",
                asText(replayMetadata.get("replayFallbackReason")));
    }

    public RunComparison compare(String baselineTraceId, String candidateTraceId) {
        RunDetail baseline = detail(baselineTraceId);
        RunDetail candidate = detail(candidateTraceId);
        return new RunComparison(
                baseline.summary(),
                candidate.summary(),
                summaryDiffs(baseline.summary(), candidate.summary()),
                spanDiffs(baseline.spans(), candidate.spans()),
                toolDiffs(baseline.toolCalls(), candidate.toolCalls()),
                guardDiffs(baseline.guardDecisions(), candidate.guardDecisions()));
    }

    private List<FailureCluster> failureClusters(List<RunDetail> details) {
        LinkedHashMap<String, FailureClusterAccumulator> grouped = new LinkedHashMap<>();
        for (RunDetail detail : details) {
            RunSummary summary = detail.summary();
            if (summary == null || "SUCCESS".equalsIgnoreCase(summary.status())) {
                continue;
            }
            SpanView failedSpan = detail.spans().stream()
                    .filter(span -> !"SUCCESS".equalsIgnoreCase(span.status()))
                    .findFirst()
                    .orElse(null);
            ToolCallView failedTool = detail.toolCalls().stream()
                    .filter(tool -> !tool.success())
                    .findFirst()
                    .orElse(null);
            GuardDecisionView deniedGuard = detail.guardDecisions().stream()
                    .filter(guard -> "DENY".equalsIgnoreCase(guard.decision()))
                    .findFirst()
                    .orElse(null);
            String errorType = firstText(
                    failedSpan == null ? null : failedSpan.errorCode(),
                    failedTool == null ? null : failedTool.errorCode(),
                    deniedGuard == null ? null : deniedGuard.decisionType(),
                    summary.fallback() ? "HYBRID_FALLBACK" : null,
                    "RUN_ERROR");
            String nodeId = failedSpan == null ? null : failedSpan.nodeId();
            String toolName = firstText(
                    failedSpan == null ? null : failedSpan.toolName(),
                    failedTool == null ? null : failedTool.toolName());
            String key = String.join("|",
                    normalizeKey(groupIdentityKey(summary)),
                    normalizeKey(groupVersionKey(summary)),
                    normalizeKey(errorType),
                    normalizeKey(nodeId),
                    normalizeKey(toolName));
            FailureClusterAccumulator accumulator = grouped.computeIfAbsent(key, ignored -> FailureClusterAccumulator.fromSummary(
                    summary,
                    errorType,
                    nodeId,
                    toolName));
            accumulator.add(detail, firstText(
                    failedSpan == null ? null : failedSpan.errorMessage(),
                    failedTool == null ? null : failedTool.resultSummary(),
                    deniedGuard == null ? null : deniedGuard.reason(),
                    summary.fallbackReason()));
        }
        return grouped.values().stream()
                .map(FailureClusterAccumulator::toCluster)
                .sorted(Comparator
                        .comparing(FailureCluster::count).reversed()
                        .thenComparing(FailureCluster::lastSeenAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(20)
                .toList();
    }

    private List<VersionComparison> versionComparisons(List<RunDetail> details) {
        Map<String, List<RunDetail>> grouped = details.stream()
                .filter(detail -> detail.summary() != null)
                .collect(Collectors.groupingBy(detail -> {
                    RunSummary summary = detail.summary();
                    return String.join("|",
                            normalizeKey(groupIdentityKey(summary)),
                            normalizeKey(groupVersionKey(summary)));
                }, LinkedHashMap::new, Collectors.toList()));
        return grouped.values().stream()
                .map(rows -> {
                    RunSummary sample = rows.get(0).summary();
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
                            .map(detail -> detail.summary().latencyMs())
                            .filter(Objects::nonNull)
                            .sorted()
                            .toList();
                    int avgLatency = total == 0 ? 0 : (int) Math.round(rows.stream()
                            .map(RunDetail::summary)
                            .map(RunSummary::latencyMs)
                            .filter(Objects::nonNull)
                            .mapToInt(Integer::intValue)
                            .average()
                            .orElse(0D));
                    int avgToken = total == 0 ? 0 : (int) Math.round(rows.stream()
                            .map(RunDetail::summary)
                            .map(RunSummary::tokenCost)
                            .filter(Objects::nonNull)
                            .mapToInt(Integer::intValue)
                            .average()
                            .orElse(0D));
                    RunSummary latest = rows.stream()
                            .map(RunDetail::summary)
                            .max(Comparator.comparing(RunSummary::startedAt, Comparator.nullsLast(LocalDateTime::compareTo)))
                            .orElse(sample);
                    return new VersionComparison(
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
                        .comparing(VersionComparison::failureCount).reversed()
                        .thenComparing(VersionComparison::runCount, Comparator.reverseOrder()))
                .toList();
    }

    private List<DiffItem> summaryDiffs(RunSummary baseline, RunSummary candidate) {
        List<DiffItem> diffs = new ArrayList<>();
        addDiff(diffs, "status", baseline.status(), candidate.status());
        addDiff(diffs, "version", baseline.version(), candidate.version());
        addDiff(diffs, "runtimePlacement", baseline.runtimePlacement(), candidate.runtimePlacement());
        addDiff(diffs, "latencyMs", baseline.latencyMs(), candidate.latencyMs());
        addDiff(diffs, "tokenCost", baseline.tokenCost(), candidate.tokenCost());
        addDiff(diffs, "errorCount", baseline.errorCount(), candidate.errorCount());
        addDiff(diffs, "fallback", baseline.fallback(), candidate.fallback());
        return diffs;
    }

    private List<SpanDiff> spanDiffs(List<SpanView> baseline, List<SpanView> candidate) {
        LinkedHashMap<String, SpanView> baselineMap = new LinkedHashMap<>();
        LinkedHashMap<String, SpanView> candidateMap = new LinkedHashMap<>();
        baseline.forEach(span -> baselineMap.put(spanKey(span), span));
        candidate.forEach(span -> candidateMap.put(spanKey(span), span));
        return unionKeys(baselineMap, candidateMap).stream()
                .map(key -> {
                    SpanView left = baselineMap.get(key);
                    SpanView right = candidateMap.get(key);
                    List<DiffItem> diffs = new ArrayList<>();
                    if (left == null || right == null) {
                        diffs.add(new DiffItem("presence", left == null ? null : "present", right == null ? null : "present", true));
                    } else {
                        addDiff(diffs, "status", left.status(), right.status());
                        addDiff(diffs, "latencyMs", left.latencyMs(), right.latencyMs());
                        addDiff(diffs, "errorCode", left.errorCode(), right.errorCode());
                        addDiff(diffs, "errorMessage", left.errorMessage(), right.errorMessage());
                        addDiff(diffs, "outputSummary", left.outputSummary(), right.outputSummary());
                    }
                    return new SpanDiff(key, left, right, diffs, hasChanged(diffs));
                })
                .toList();
    }

    private List<ToolDiff> toolDiffs(List<ToolCallView> baseline, List<ToolCallView> candidate) {
        LinkedHashMap<String, ToolCallView> baselineMap = new LinkedHashMap<>();
        LinkedHashMap<String, ToolCallView> candidateMap = new LinkedHashMap<>();
        baseline.forEach(tool -> baselineMap.put(toolKey(tool), tool));
        candidate.forEach(tool -> candidateMap.put(toolKey(tool), tool));
        return unionKeys(baselineMap, candidateMap).stream()
                .map(key -> {
                    ToolCallView left = baselineMap.get(key);
                    ToolCallView right = candidateMap.get(key);
                    List<DiffItem> diffs = new ArrayList<>();
                    if (left == null || right == null) {
                        diffs.add(new DiffItem("presence", left == null ? null : "present", right == null ? null : "present", true));
                    } else {
                        addDiff(diffs, "success", left.success(), right.success());
                        addDiff(diffs, "elapsedMs", left.elapsedMs(), right.elapsedMs());
                        addDiff(diffs, "errorCode", left.errorCode(), right.errorCode());
                        addDiff(diffs, "resultSummary", left.resultSummary(), right.resultSummary());
                    }
                    return new ToolDiff(key, left, right, diffs, hasChanged(diffs));
                })
                .toList();
    }

    private List<GuardDiff> guardDiffs(List<GuardDecisionView> baseline, List<GuardDecisionView> candidate) {
        LinkedHashMap<String, GuardDecisionView> baselineMap = new LinkedHashMap<>();
        LinkedHashMap<String, GuardDecisionView> candidateMap = new LinkedHashMap<>();
        baseline.forEach(guard -> baselineMap.put(guardKey(guard), guard));
        candidate.forEach(guard -> candidateMap.put(guardKey(guard), guard));
        return unionKeys(baselineMap, candidateMap).stream()
                .map(key -> {
                    GuardDecisionView left = baselineMap.get(key);
                    GuardDecisionView right = candidateMap.get(key);
                    List<DiffItem> diffs = new ArrayList<>();
                    if (left == null || right == null) {
                        diffs.add(new DiffItem("presence", left == null ? null : "present", right == null ? null : "present", true));
                    } else {
                        addDiff(diffs, "decision", left.decision(), right.decision());
                        addDiff(diffs, "reason", left.reason(), right.reason());
                    }
                    return new GuardDiff(key, left, right, diffs, hasChanged(diffs));
                })
                .toList();
    }

    private RunSummary buildSummary(String traceId,
                                    List<ToolCallLogEntity> toolLogs,
                                    List<AgentTraceSpanEntity> spans,
                                    List<GuardDecisionLogEntity> decisions,
                                    Map<String, Object> metadata,
                                    AgentRuntimeProfile agent) {
        LocalDateTime startedAt = spans.stream().map(AgentTraceSpanEntity::getStartedAt).filter(Objects::nonNull)
                .min(LocalDateTime::compareTo).orElseGet(() -> toolLogs.stream()
                        .map(ToolCallLogEntity::getCreateTime).filter(Objects::nonNull)
                        .min(LocalDateTime::compareTo).orElse(null));
        LocalDateTime endedAt = spans.stream().map(AgentTraceSpanEntity::getEndedAt).filter(Objects::nonNull)
                .max(LocalDateTime::compareTo).orElseGet(() -> toolLogs.stream()
                        .map(ToolCallLogEntity::getCreateTime).filter(Objects::nonNull)
                        .max(LocalDateTime::compareTo).orElse(startedAt));
        int latency = spans.stream().map(AgentTraceSpanEntity::getLatencyMs).filter(Objects::nonNull).mapToInt(Integer::intValue).max()
                .orElse(millisBetween(startedAt, endedAt));
        int tokens = spans.stream().map(AgentTraceSpanEntity::getTokenCost).filter(Objects::nonNull).mapToInt(Integer::intValue).sum()
                + toolLogs.stream().map(ToolCallLogEntity::getTokenCost).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        boolean waiting = spans.stream().anyMatch(span -> "WAITING".equalsIgnoreCase(span.getStatus()));
        long errorCount = spans.stream().filter(span -> "ERROR".equalsIgnoreCase(span.getStatus())).count()
                + toolLogs.stream().filter(log -> !Boolean.TRUE.equals(log.getSuccess())).count()
                + decisions.stream().filter(decision -> "DENY".equalsIgnoreCase(decision.getDecision())).count();
        String status = errorCount == 0 ? (waiting ? "WAITING" : "SUCCESS") : "ERROR";
        String fallbackReason = asText(metadata.get("embeddedFallbackReason"));
        return createRunSummary(
                traceId,
                status,
                agent == null ? firstSpan(spans).map(AgentTraceSpanEntity::getAgentId).orElse(null) : agent.getId(),
                agent == null ? firstText(firstSpan(spans).map(AgentTraceSpanEntity::getAgentName).orElse(null),
                        firstTool(toolLogs).map(ToolCallLogEntity::getAgentName).orElse(null)) : agent.getName(),
                asText(metadata.get("version")),
                asLong(metadata.get("versionId")),
                firstText(asText(metadata.get("runtimeType")), firstSpan(spans).map(AgentTraceSpanEntity::getRuntimeType).orElse(null)),
                firstText(asText(metadata.get("runtimePlacement")), agent == null ? null : agent.getRuntimePlacement()),
                asText(metadata.get("graphCode")),
                firstTool(toolLogs).map(ToolCallLogEntity::getSessionId).orElse(null),
                firstTool(toolLogs).map(ToolCallLogEntity::getUserId).orElse(null),
                firstText(asText(metadata.get("intentType")), firstTool(toolLogs).map(ToolCallLogEntity::getIntentType).orElse(null)),
                startedAt,
                endedAt,
                latency,
                tokens,
                spans.size(),
                toolLogs.size(),
                (int) errorCount,
                StringUtils.hasText(fallbackReason),
                asText(metadata.get("dispatchUrl")),
                fallbackReason,
                metadata);
    }

    private RunSummary createRunSummary(String traceId,
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
                                         Map<String, Object> metadata) {
        Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;
        String sourceType = asText(safeMetadata.get("sourceType"));
        String workflowId = firstText(
                asText(safeMetadata.get("workflowId")),
                asText(safeMetadata.get("resolvedWorkflowId")));
        String sourceId = firstText(
                asText(safeMetadata.get("sourceId")),
                isWorkflowSourceType(sourceType) ? workflowId : null);
        return new RunSummary(
                traceId,
                status,
                agentId,
                agentName,
                version,
                versionId,
                runtimeType,
                runtimePlacement,
                graphCode,
                sessionId,
                userId,
                intentType,
                startedAt,
                endedAt,
                latencyMs,
                tokenCost,
                nodeCount,
                toolCallCount,
                errorCount,
                fallback,
                dispatchUrl,
                fallbackReason,
                workflowId,
                asText(safeMetadata.get("workflowKeySlug")),
                asText(safeMetadata.get("workflowVersion")),
                asLong(safeMetadata.get("workflowVersionId")),
                asText(safeMetadata.get("entryAgentId")),
                asText(safeMetadata.get("entryAgentKeySlug")),
                sourceType,
                sourceId,
                safeMetadata.isEmpty() ? null : safeMetadata);
    }

    private boolean isWorkflowSourceType(String sourceType) {
        return StringUtils.hasText(sourceType) && sourceType.trim().toUpperCase().startsWith("WORKFLOW");
    }

    private boolean isWorkflowSummary(RunSummary summary) {
        if (summary == null) {
            return false;
        }
        return isWorkflowSourceType(summary.sourceType()) || StringUtils.hasText(summary.workflowId());
    }

    private boolean isWorkflowMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        return isWorkflowSourceType(asText(metadata.get("sourceType"))) || StringUtils.hasText(asText(metadata.get("workflowId")));
    }

    private Map<String, Object> buildReplayMetadata(String normalizedTraceId,
                                                    Map<String, Object> metadata,
                                                    boolean useSnapshot) {
        Map<String, Object> replayMetadata = new LinkedHashMap<>();
        replayMetadata.put("replay", true);
        replayMetadata.put("replayOfTraceId", normalizedTraceId);
        replayMetadata.put("replayUseSnapshot", useSnapshot);
        replayMetadata.put("replaySourceVersion", asText(metadata.get("version")));
        replayMetadata.put("replaySourceVersionId", asLong(metadata.get("versionId")));
        return replayMetadata;
    }

    private void enrichWorkflowReplayMetadata(Map<String, Object> replayMetadata, Map<String, Object> source) {
        if (replayMetadata == null || source == null) {
            return;
        }
        putIfText(replayMetadata, "workflowId", firstText(asText(source.get("workflowId")), asText(source.get("resolvedWorkflowId"))));
        putIfText(replayMetadata, "workflowKeySlug", asText(source.get("workflowKeySlug")));
        putIfText(replayMetadata, "workflowVersion", asText(source.get("workflowVersion")));
        putIfPresent(replayMetadata, "workflowVersionId", source.get("workflowVersionId"));
        putIfText(replayMetadata, "entryAgentId", asText(source.get("entryAgentId")));
        putIfText(replayMetadata, "entryAgentKeySlug", asText(source.get("entryAgentKeySlug")));
        putIfText(replayMetadata, "sourceType", asText(source.get("sourceType")));
        putIfText(replayMetadata, "sourceId", firstText(asText(source.get("sourceId")), asText(source.get("workflowId"))));
    }

    private void enrichWorkflowReplayMetadata(Map<String, Object> replayMetadata, GraphRuntimeContext runtimeContext) {
        if (replayMetadata == null || runtimeContext == null) {
            return;
        }
        putIfText(replayMetadata, "sourceType", runtimeContext.getSourceType());
        putIfText(replayMetadata, "sourceId", runtimeContext.getSourceId());
        putIfText(replayMetadata, "workflowId", runtimeContext.getSourceId());
        putIfText(replayMetadata, "workflowKeySlug", runtimeContext.getSourceKeySlug());
        putIfText(replayMetadata, "workflowVersion", runtimeContext.getSourceVersion());
        putIfPresent(replayMetadata, "workflowVersionId", runtimeContext.getSourceVersionId());
        Map<String, Object> extra = runtimeContext.getExtra();
        if (extra != null) {
            putIfText(replayMetadata, "entryAgentId", asText(extra.get("entryAgentId")));
            putIfText(replayMetadata, "entryAgentKeySlug", asText(extra.get("entryAgentKeySlug")));
            putIfText(replayMetadata, "workflowId", firstText(asText(extra.get("workflowId")), runtimeContext.getSourceId()));
        }
    }

    private WorkflowReplayResolution resolveWorkflowReplay(Map<String, Object> metadata, boolean useSnapshot) {
        String workflowId = firstText(
                asText(metadata.get("workflowId")),
                asText(metadata.get("resolvedWorkflowId")),
                isWorkflowSourceType(asText(metadata.get("sourceType"))) ? asText(metadata.get("sourceId")) : null);
        if (!StringUtils.hasText(workflowId)) {
            return new WorkflowReplayResolution(null, null, null, null, "trace metadata 缺少 workflowId/sourceId");
        }
        WorkflowDefinitionEntity workflow = workflowDefinitionService.findById(workflowId).orElse(null);
        if (workflow == null) {
            return new WorkflowReplayResolution(null, null, null, null, "Workflow 定义不存在: " + workflowId);
        }

        Long workflowVersionId = asLong(metadata.get("workflowVersionId"));
        WorkflowVersionEntity versionEntity = null;
        String fallbackReason = null;
        if (useSnapshot && workflowVersionId != null) {
            versionEntity = workflowVersionMapper.selectById(workflowVersionId);
            if (versionEntity == null || !workflowId.equals(versionEntity.getWorkflowId())) {
                fallbackReason = "Workflow 版本快照不可用(versionId=" + workflowVersionId + ")，回退到当前 Workflow 图";
                versionEntity = null;
            }
        }

        String entryAgentId = asText(metadata.get("entryAgentId"));
        if (!StringUtils.hasText(entryAgentId)) {
            return new WorkflowReplayResolution(null, workflow, versionEntity, null,
                    "trace metadata 缺少 entryAgentId，无法构造 GraphRuntimeContext");
        }
        AgentEntryEntity entryAgent = agentEntryService.findById(entryAgentId).orElse(null);
        if (entryAgent == null) {
            return new WorkflowReplayResolution(null, workflow, versionEntity, null,
                    "入口 AgentEntry 不存在: " + entryAgentId);
        }

        Map<String, Object> runtimeMetadata = new LinkedHashMap<>(metadata);
        runtimeMetadata.put("replay", true);
        try {
            WorkflowRuntimeGraphAdapter.RuntimeGraph runtimeGraph = workflowRuntimeGraphAdapter.toRuntimeGraph(
                    entryAgent,
                    workflow,
                    versionEntity,
                    WorkflowRuntimeGraphAdapter.RuntimeContextOptions.builder()
                            .metadata(runtimeMetadata)
                            .build());
            return new WorkflowReplayResolution(runtimeGraph, workflow, versionEntity, entryAgent, fallbackReason);
        } catch (IllegalArgumentException ex) {
            return new WorkflowReplayResolution(null, workflow, versionEntity, entryAgent, ex.getMessage());
        }
    }

    private ReplayResult buildReplayResult(String normalizedTraceId,
                                           String sessionId,
                                           String userId,
                                           String message,
                                           AgentResult result,
                                           Map<String, Object> replayMetadata,
                                           WorkflowReplayResolution workflowReplay,
                                           AgentRuntimeProfile legacyAgent,
                                           String executionPath,
                                           String fallbackReason) {
        Map<String, Object> resultMetadata = result.getMetadata() == null ? Map.of() : result.getMetadata();
        String replayTraceId = asText(resultMetadata.get("traceId"));
        GraphRuntimeContext runtimeContext = workflowReplay == null || workflowReplay.graph() == null
                ? null
                : workflowReplay.graph().runtimeContext();
        WorkflowDefinitionEntity workflow = workflowReplay == null ? null : workflowReplay.workflow();
        AgentEntryEntity entryAgent = workflowReplay == null ? null : workflowReplay.entryAgent();
        WorkflowVersionEntity versionEntity = workflowReplay == null ? null : workflowReplay.version();
        String agentId = legacyAgent != null
                ? legacyAgent.getId()
                : (entryAgent == null ? asText(replayMetadata.get("entryAgentId")) : entryAgent.getId());
        String agentName = legacyAgent != null
                ? legacyAgent.getName()
                : (runtimeContext == null ? null : runtimeContext.getName());
        String version = legacyAgent != null
                ? asText(replayMetadata.get("replaySourceVersion"))
                : (versionEntity == null ? asText(replayMetadata.get("replaySourceVersion")) : versionEntity.getVersion());
        Long versionId = versionEntity == null
                ? asLong(replayMetadata.get("replaySourceVersionId"))
                : versionEntity.getId();
        return new ReplayResult(
                normalizedTraceId,
                replayTraceId,
                sessionId,
                userId,
                agentId,
                agentName,
                version,
                versionId,
                message,
                result.isSuccess(),
                result.getAnswer(),
                resultMetadata,
                workflow == null ? asText(replayMetadata.get("workflowId")) : workflow.getId(),
                workflow == null ? asText(replayMetadata.get("workflowKeySlug")) : workflow.getKeySlug(),
                runtimeContext == null ? asText(replayMetadata.get("workflowVersion")) : runtimeContext.getSourceVersion(),
                runtimeContext == null ? asLong(replayMetadata.get("workflowVersionId")) : runtimeContext.getSourceVersionId(),
                entryAgent == null ? asText(replayMetadata.get("entryAgentId")) : entryAgent.getId(),
                entryAgent == null ? asText(replayMetadata.get("entryAgentKeySlug")) : entryAgent.getKeySlug(),
                runtimeContext == null ? asText(replayMetadata.get("sourceType")) : runtimeContext.getSourceType(),
                runtimeContext == null ? asText(replayMetadata.get("sourceId")) : runtimeContext.getSourceId(),
                executionPath,
                fallbackReason);
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private record WorkflowReplayResolution(
            WorkflowRuntimeGraphAdapter.RuntimeGraph graph,
            WorkflowDefinitionEntity workflow,
            WorkflowVersionEntity version,
            AgentEntryEntity entryAgent,
            String fallbackReason
    ) {
        WorkflowReplayResolution {
            if (fallbackReason != null && fallbackReason.isBlank()) {
                fallbackReason = null;
            }
        }
    }

    String groupIdentityKey(RunSummary summary) {
        if (isWorkflowSummary(summary)) {
            return firstText(summary.workflowId(), summary.sourceId(), summary.agentId(), summary.agentName());
        }
        return firstText(summary.agentId(), summary.agentName());
    }

    String groupVersionKey(RunSummary summary) {
        if (isWorkflowSummary(summary)) {
            return firstText(asText(summary.workflowVersionId()), summary.workflowVersion(), asText(summary.versionId()), summary.version());
        }
        return firstText(asText(summary.versionId()), summary.version());
    }

    private Map<String, Object> mergedMetadata(List<AgentTraceSpanEntity> spans) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (AgentTraceSpanEntity span : spans) {
            merged.putAll(parseMap(span.getMetadataJson()));
        }
        return merged;
    }

    private List<WorkflowPathItem> workflowPath(List<SpanView> spans, GraphSpec spec) {
        if (spans == null || spans.isEmpty()) {
            return List.of();
        }
        List<SpanView> nodeSpans = spans.stream()
                .filter(span -> StringUtils.hasText(span.nodeId()))
                .filter(span -> !"AGENT_RUN".equalsIgnoreCase(span.spanType()))
                .toList();
        if (nodeSpans.isEmpty()) {
            return List.of();
        }
        List<WorkflowPathItem> path = new ArrayList<>();
        for (int i = 0; i < nodeSpans.size(); i++) {
            SpanView current = nodeSpans.get(i);
            SpanView next = i + 1 < nodeSpans.size() ? nodeSpans.get(i + 1) : null;
            GraphSpec.Edge edge = next == null ? null : findGraphEdge(spec, current.nodeId(), next.nodeId());
            path.add(new WorkflowPathItem(
                    current.nodeId(),
                    next == null ? null : next.nodeId(),
                    edge == null ? null : edge.getCondition(),
                    asText(current.metadata().get("lastRoute")),
                    current.status(),
                    asText(current.metadata().get("workflowStatus")),
                    asText(current.metadata().get("interactionId")),
                    current.spanId(),
                    current.startedAt(),
                    current.endedAt()));
        }
        return path;
    }

    private GraphSpec.Edge findGraphEdge(GraphSpec spec, String from, String to) {
        if (spec == null || spec.getEdges() == null || !StringUtils.hasText(from) || !StringUtils.hasText(to)) {
            return null;
        }
        return spec.getEdges().stream()
                .filter(edge -> from.equals(edge.getFrom()) && to.equals(edge.getTo()))
                .findFirst()
                .orElse(null);
    }

    private AgentRuntimeProfile resolveCurrentProfile(List<AgentTraceSpanEntity> spans, List<ToolCallLogEntity> toolLogs) {
        Optional<String> agentId = firstSpan(spans).map(AgentTraceSpanEntity::getAgentId).filter(StringUtils::hasText);
        if (agentId.isPresent()) {
            return agentEntryService.findById(agentId.get())
                    .map(entry -> AgentRuntimeProfile.fromAgentEntry(entry, objectMapper))
                    .orElse(null);
        }
        String entryAgentId = asText(mergedMetadata(spans).get("entryAgentId"));
        if (StringUtils.hasText(entryAgentId)) {
            return agentEntryService.findById(entryAgentId)
                    .map(entry -> AgentRuntimeProfile.fromAgentEntry(entry, objectMapper))
                    .orElse(null);
        }
        return null;
    }

    private String replayMessage(List<AgentTraceSpanEntity> spans, List<ToolCallLogEntity> toolLogs) {
        String rootInput = spans.stream()
                .filter(span -> "AGENT_RUN".equalsIgnoreCase(span.getSpanType()))
                .map(AgentTraceSpanEntity::getInputSummary)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        if (StringUtils.hasText(rootInput)) {
            return rootInput;
        }
        for (ToolCallLogEntity log : toolLogs) {
            Map<String, Object> args = parseMap(log.getArgsJson());
            String userInput = firstText(asText(args.get("userInput")), asText(args.get("input")), asText(args.get("message")));
            if (StringUtils.hasText(userInput)) {
                return userInput;
            }
        }
        return null;
    }

    private List<String> repairHints(RunSummary summary, List<SpanView> spans, List<GuardDecisionView> guards) {
        List<String> hints = new ArrayList<>();
        if (summary == null || "SUCCESS".equals(summary.status())) {
            return hints;
        }
        spans.stream()
                .filter(span -> !"SUCCESS".equalsIgnoreCase(span.status()))
                .findFirst()
                .ifPresent(span -> hints.add(span.nodeId() == null || span.nodeId().isBlank()
                        ? "优先查看失败 span 的错误信息和 Runtime 配置。"
                        : "优先回到 Agent Studio 定位节点 " + span.nodeId() + "。"));
        if (summary.fallback()) {
            hints.add("本次运行发生 HYBRID fallback，请检查目标 Runtime 实例健康状态和 dispatchUrl。");
        }
        guards.stream()
                .filter(guard -> "DENY".equalsIgnoreCase(guard.decision()))
                .findFirst()
                .ifPresent(guard -> hints.add("存在治理拒绝决策，请检查 " + guard.targetKind() + " / " + guard.targetName() + " 的策略。"));
        if (hints.isEmpty()) {
            hints.add("建议从 Trace 节点、Tool 调用和 Guard 决策三处交叉定位。");
        }
        return hints;
    }

    private Map<String, Object> parseMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of("raw", json);
        }
    }

    private Optional<AgentTraceSpanEntity> firstSpan(List<AgentTraceSpanEntity> spans) {
        return spans.stream().findFirst();
    }

    private Optional<ToolCallLogEntity> firstTool(List<ToolCallLogEntity> logs) {
        return logs.stream().findFirst();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private <T> T firstNonNull(T first, T second) {
        return first == null ? second : first;
    }

    private int millisBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return (int) Math.max(0, java.time.Duration.between(start, end).toMillis());
    }

    private String normalizeKey(String value) {
        return StringUtils.hasText(value) ? value.trim() : "-";
    }

    private String spanKey(SpanView span) {
        return normalizeKey(firstText(span.nodeId(), span.toolName(), span.spanType(), span.spanId()));
    }

    private String toolKey(ToolCallView tool) {
        return normalizeKey(tool.toolName());
    }

    private String guardKey(GuardDecisionView guard) {
        return String.join("|",
                normalizeKey(guard.decisionType()),
                normalizeKey(guard.targetKind()),
                normalizeKey(guard.targetName()));
    }

    private <T> List<String> unionKeys(Map<String, T> baseline, Map<String, T> candidate) {
        LinkedHashMap<String, Boolean> keys = new LinkedHashMap<>();
        baseline.keySet().forEach(key -> keys.put(key, true));
        candidate.keySet().forEach(key -> keys.putIfAbsent(key, true));
        return new ArrayList<>(keys.keySet());
    }

    private void addDiff(List<DiffItem> diffs, String field, Object baseline, Object candidate) {
        boolean changed = !Objects.equals(baseline, candidate);
        diffs.add(new DiffItem(field, baseline, candidate, changed));
    }

    private boolean hasChanged(List<DiffItem> diffs) {
        return diffs.stream().anyMatch(DiffItem::changed);
    }

    private int percentile(List<Integer> values, int percentile) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil((percentile / 100D) * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private static class FailureClusterAccumulator {
        private final String agentId;
        private final String agentName;
        private final String version;
        private final Long versionId;
        private final String runtimeType;
        private final String runtimePlacement;
        private final String workflowId;
        private final String workflowKeySlug;
        private final String workflowVersion;
        private final Long workflowVersionId;
        private final String sourceType;
        private final String sourceId;
        private final String errorType;
        private final String nodeId;
        private final String toolName;
        private int count;
        private int fallbackCount;
        private int totalLatencyMs;
        private LocalDateTime firstSeenAt;
        private LocalDateTime lastSeenAt;
        private String sampleTraceId;
        private String sampleError;
        private final List<String> traceIds = new ArrayList<>();
        private final List<String> repairHints = new ArrayList<>();

        private static FailureClusterAccumulator fromSummary(RunSummary summary,
                                                             String errorType,
                                                             String nodeId,
                                                             String toolName) {
            return new FailureClusterAccumulator(
                    summary.agentId(),
                    summary.agentName(),
                    summary.version(),
                    summary.versionId(),
                    summary.runtimeType(),
                    summary.runtimePlacement(),
                    summary.workflowId(),
                    summary.workflowKeySlug(),
                    summary.workflowVersion(),
                    summary.workflowVersionId(),
                    summary.sourceType(),
                    summary.sourceId(),
                    errorType,
                    nodeId,
                    toolName);
        }

        private FailureClusterAccumulator(String agentId,
                                          String agentName,
                                          String version,
                                          Long versionId,
                                          String runtimeType,
                                          String runtimePlacement,
                                          String workflowId,
                                          String workflowKeySlug,
                                          String workflowVersion,
                                          Long workflowVersionId,
                                          String sourceType,
                                          String sourceId,
                                          String errorType,
                                          String nodeId,
                                          String toolName) {
            this.agentId = agentId;
            this.agentName = agentName;
            this.version = version;
            this.versionId = versionId;
            this.runtimeType = runtimeType;
            this.runtimePlacement = runtimePlacement;
            this.workflowId = workflowId;
            this.workflowKeySlug = workflowKeySlug;
            this.workflowVersion = workflowVersion;
            this.workflowVersionId = workflowVersionId;
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.errorType = errorType;
            this.nodeId = nodeId;
            this.toolName = toolName;
        }

        private void add(RunDetail detail, String error) {
            RunSummary summary = detail.summary();
            count++;
            if (summary.fallback()) {
                fallbackCount++;
            }
            totalLatencyMs += summary.latencyMs() == null ? 0 : summary.latencyMs();
            if (traceIds.size() < 5) {
                traceIds.add(summary.traceId());
            }
            if (sampleTraceId == null) {
                sampleTraceId = summary.traceId();
            }
            if (!StringUtils.hasText(sampleError) && StringUtils.hasText(error)) {
                sampleError = error;
            }
            if (detail.repairHints() != null) {
                for (String hint : detail.repairHints()) {
                    if (repairHints.size() >= 3) {
                        break;
                    }
                    if (StringUtils.hasText(hint) && !repairHints.contains(hint)) {
                        repairHints.add(hint);
                    }
                }
            }
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

        private FailureCluster toCluster() {
            return new FailureCluster(
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
                    repairHints,
                    workflowId,
                    workflowKeySlug,
                    workflowVersion,
                    workflowVersionId,
                    sourceType,
                    sourceId);
        }
    }

    public record RunDiagnostics(
            List<FailureCluster> failureClusters,
            List<VersionComparison> versionComparisons
    ) {}

    public record ReplayRequest(
            String messageOverride,
            String sessionId,
            String userId,
            List<String> roles,
            Boolean useSnapshot
    ) {}

    public record ReplayResult(
            String originalTraceId,
            String replayTraceId,
            String sessionId,
            String userId,
            String agentId,
            String agentName,
            String version,
            Long versionId,
            String message,
            boolean success,
            String answer,
            Map<String, Object> metadata,
            String workflowId,
            String workflowKeySlug,
            String workflowVersion,
            Long workflowVersionId,
            String entryAgentId,
            String entryAgentKeySlug,
            String sourceType,
            String sourceId,
            String executionPath,
            String fallbackReason
    ) {}

    public record RunComparison(
            RunSummary baseline,
            RunSummary candidate,
            List<DiffItem> summaryDiffs,
            List<SpanDiff> spanDiffs,
            List<ToolDiff> toolDiffs,
            List<GuardDiff> guardDiffs
    ) {}

    public record DiffItem(
            String field,
            Object baseline,
            Object candidate,
            boolean changed
    ) {}

    public record SpanDiff(
            String key,
            SpanView baseline,
            SpanView candidate,
            List<DiffItem> diffs,
            boolean changed
    ) {}

    public record ToolDiff(
            String key,
            ToolCallView baseline,
            ToolCallView candidate,
            List<DiffItem> diffs,
            boolean changed
    ) {}

    public record GuardDiff(
            String key,
            GuardDecisionView baseline,
            GuardDecisionView candidate,
            List<DiffItem> diffs,
            boolean changed
    ) {}

    public record FailureCluster(
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
            String sourceId
    ) {}

    public record VersionComparison(
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
            String sourceId
    ) {}

    public record RunDetail(
            RunSummary summary,
            List<SpanView> spans,
            List<ToolCallView> toolCalls,
            List<GuardDecisionView> guardDecisions,
            RunSnapshot snapshot,
            List<WorkflowPathItem> workflowPath,
            List<String> repairHints
    ) {}

    public record WorkflowPathItem(
            String fromNodeId,
            String toNodeId,
            String condition,
            String route,
            String status,
            String workflowStatus,
            String interactionId,
            String spanId,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {}

    public record RunSummary(
            String traceId,
            String status,
            /** 历史字段：Agent 运行时为 Agent id；Workflow 运行可能为 workflow sourceId */
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
            /** AgentEntry / chat·embed 入口 id，不是 WorkflowDefinition id */
            String entryAgentId,
            String entryAgentKeySlug,
            String sourceType,
            String sourceId,
            Map<String, Object> metadata
    ) {}

    public record SpanView(
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
            LocalDateTime endedAt
    ) {}

    public record ToolCallView(
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
            LocalDateTime createdAt
    ) {}

    public record GuardDecisionView(
            Long id,
            String decisionType,
            String targetKind,
            String targetName,
            String decision,
            String reason,
            Map<String, Object> metadata,
            LocalDateTime createdAt
    ) {}

    public record RunSnapshot(
            String agentId,
            String agentName,
            String keySlug,
            String runtimeType,
            String runtimePlacement,
            Map<String, Object> runtimeConfig,
            Object graphSpec,
            String snapshotJson
    ) {}
}
