package com.enterprise.ai.agent.runops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.agent.persist.AgentVersionEntity;
import com.enterprise.ai.agent.agent.persist.AgentVersionMapper;
import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.graph.AgentGraphSpec;
import com.enterprise.ai.agent.governance.GuardDecisionLogEntity;
import com.enterprise.ai.agent.governance.GuardDecisionLogService;
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
    private final AgentVersionMapper versionMapper;
    private final AgentDefinitionService agentDefinitionService;
    private final AgentRouter agentRouter;
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
        AgentVersionEntity version = resolveVersion(metadata);
        AgentDefinition currentAgent = resolveCurrentAgent(spans, toolLogs);
        AgentDefinition snapshot = version == null ? null : readSnapshot(version);
        AgentDefinition effectiveAgent = snapshot == null ? currentAgent : snapshot;

        RunSummary summary = buildSummary(normalizedTraceId, toolLogs, spans, decisions, metadata, version, effectiveAgent);
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
                effectiveAgent.getGraphSpec(),
                version == null ? null : version.getSnapshotJson());
        return new RunDetail(summary, spanViews, toolViews, guardViews, runSnapshot,
                workflowPath(spanViews, effectiveAgent == null ? null : effectiveAgent.getGraphSpec()),
                repairHints(summary, spanViews, guardViews));
    }

    public List<RunSummary> recent(String userId, int limit, int days) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<ToolCallLogService.TraceSummary> toolTraces = toolCallLogService.listRecentTraces(userId, safeLimit, days);
        LinkedHashMap<String, RunSummary> rows = new LinkedHashMap<>();
        for (ToolCallLogService.TraceSummary trace : toolTraces) {
            rows.put(trace.traceId(), new RunSummary(
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
                    null));
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
                rows.put(span.getTraceId(), new RunSummary(
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
                        asText(metadata.get("embeddedFallbackReason"))));
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
        AgentVersionEntity version = resolveVersion(metadata);
        AgentDefinition currentAgent = resolveCurrentAgent(spans, toolLogs);
        AgentDefinition snapshot = version == null ? null : readSnapshot(version);
        boolean useSnapshot = request == null || request.useSnapshot() == null || request.useSnapshot();
        AgentDefinition agent = useSnapshot && snapshot != null ? snapshot : currentAgent;
        if (agent == null) {
            throw new IllegalArgumentException("无法解析重放使用的 Agent 定义: " + normalizedTraceId);
        }

        String message = firstText(request == null ? null : request.messageOverride(), replayMessage(spans, toolLogs));
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("无法从 trace 中还原运行输入，请提供 messageOverride");
        }
        String sessionId = firstText(request == null ? null : request.sessionId(),
                "replay-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        String userId = firstText(request == null ? null : request.userId(), firstTool(toolLogs).map(ToolCallLogEntity::getUserId).orElse(null), "runops-replay");
        List<String> roles = request == null || request.roles() == null ? List.of() : request.roles();
        Map<String, Object> replayMetadata = new LinkedHashMap<>();
        replayMetadata.put("replay", true);
        replayMetadata.put("replayOfTraceId", normalizedTraceId);
        replayMetadata.put("replayUseSnapshot", useSnapshot && snapshot != null);
        replayMetadata.put("replaySourceVersion", version == null ? asText(metadata.get("version")) : version.getVersion());
        replayMetadata.put("replaySourceVersionId", version == null ? asLong(metadata.get("versionId")) : version.getId());

        AgentResult result = agentRouter.executeByDefinition(agent, sessionId, userId, message, roles, replayMetadata);
        String replayTraceId = result.getMetadata() == null ? null : asText(result.getMetadata().get("traceId"));
        return new ReplayResult(
                normalizedTraceId,
                replayTraceId,
                sessionId,
                userId,
                agent.getId(),
                agent.getName(),
                version == null ? asText(metadata.get("version")) : version.getVersion(),
                version == null ? asLong(metadata.get("versionId")) : version.getId(),
                message,
                result.isSuccess(),
                result.getAnswer(),
                result.getMetadata());
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
                    normalizeKey(firstText(summary.agentId(), summary.agentName())),
                    normalizeKey(firstText(asText(summary.versionId()), summary.version())),
                    normalizeKey(errorType),
                    normalizeKey(nodeId),
                    normalizeKey(toolName));
            FailureClusterAccumulator accumulator = grouped.computeIfAbsent(key, ignored -> new FailureClusterAccumulator(
                    summary.agentId(),
                    summary.agentName(),
                    summary.version(),
                    summary.versionId(),
                    summary.runtimeType(),
                    summary.runtimePlacement(),
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
                            normalizeKey(firstText(summary.agentId(), summary.agentName())),
                            normalizeKey(firstText(asText(summary.versionId()), summary.version())));
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
                            latest.startedAt());
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
                                    AgentVersionEntity version,
                                    AgentDefinition agent) {
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
        return new RunSummary(
                traceId,
                status,
                agent == null ? firstSpan(spans).map(AgentTraceSpanEntity::getAgentId).orElse(null) : agent.getId(),
                agent == null ? firstText(firstSpan(spans).map(AgentTraceSpanEntity::getAgentName).orElse(null),
                        firstTool(toolLogs).map(ToolCallLogEntity::getAgentName).orElse(null)) : agent.getName(),
                version == null ? asText(metadata.get("version")) : version.getVersion(),
                version == null ? asLong(metadata.get("versionId")) : version.getId(),
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
                fallbackReason);
    }

    private Map<String, Object> mergedMetadata(List<AgentTraceSpanEntity> spans) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (AgentTraceSpanEntity span : spans) {
            merged.putAll(parseMap(span.getMetadataJson()));
        }
        return merged;
    }

    private List<WorkflowPathItem> workflowPath(List<SpanView> spans, AgentGraphSpec spec) {
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
            AgentGraphSpec.Edge edge = next == null ? null : findGraphEdge(spec, current.nodeId(), next.nodeId());
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

    private AgentGraphSpec.Edge findGraphEdge(AgentGraphSpec spec, String from, String to) {
        if (spec == null || spec.getEdges() == null || !StringUtils.hasText(from) || !StringUtils.hasText(to)) {
            return null;
        }
        return spec.getEdges().stream()
                .filter(edge -> from.equals(edge.getFrom()) && to.equals(edge.getTo()))
                .findFirst()
                .orElse(null);
    }

    private AgentVersionEntity resolveVersion(Map<String, Object> metadata) {
        Long versionId = asLong(metadata.get("versionId"));
        if (versionId == null) {
            return null;
        }
        return versionMapper.selectById(versionId);
    }

    private AgentDefinition readSnapshot(AgentVersionEntity version) {
        if (version == null || !StringUtils.hasText(version.getSnapshotJson())) {
            return null;
        }
        try {
            return objectMapper.readValue(version.getSnapshotJson(), AgentDefinition.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private AgentDefinition resolveCurrentAgent(List<AgentTraceSpanEntity> spans, List<ToolCallLogEntity> toolLogs) {
        Optional<String> agentId = firstSpan(spans).map(AgentTraceSpanEntity::getAgentId).filter(StringUtils::hasText);
        if (agentId.isPresent()) {
            return agentDefinitionService.findById(agentId.get()).orElse(null);
        }
        String agentName = firstText(firstSpan(spans).map(AgentTraceSpanEntity::getAgentName).orElse(null),
                firstTool(toolLogs).map(ToolCallLogEntity::getAgentName).orElse(null));
        if (!StringUtils.hasText(agentName)) {
            return null;
        }
        return agentDefinitionService.list(null).stream()
                .filter(agent -> agentName.equals(agent.getName()))
                .findFirst()
                .orElse(null);
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

        private FailureClusterAccumulator(String agentId,
                                          String agentName,
                                          String version,
                                          Long versionId,
                                          String runtimeType,
                                          String runtimePlacement,
                                          String errorType,
                                          String nodeId,
                                          String toolName) {
            this.agentId = agentId;
            this.agentName = agentName;
            this.version = version;
            this.versionId = versionId;
            this.runtimeType = runtimeType;
            this.runtimePlacement = runtimePlacement;
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
                    repairHints);
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
            Map<String, Object> metadata
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
            List<String> repairHints
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
            LocalDateTime latestStartedAt
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
            String fallbackReason
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
