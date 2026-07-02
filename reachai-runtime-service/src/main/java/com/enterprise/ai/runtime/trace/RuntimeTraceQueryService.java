package com.enterprise.ai.runtime.trace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.Optional;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class RuntimeTraceQueryService {

    private static final TypeReference<List<Map<String, Object>>> CANDIDATES_TYPE = new TypeReference<>() {
    };

    private final RuntimeToolCallLogMapper toolLogMapper;
    private final RuntimeAgentTraceSpanMapper spanMapper;
    private final ObjectMapper objectMapper;

    public Optional<RuntimeTraceDetailView> getTraceDetail(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return Optional.empty();
        }
        String normalizedTraceId = traceId.trim();
        List<RuntimeToolCallLogEntity> logs = toolLogMapper.selectList(new LambdaQueryWrapper<RuntimeToolCallLogEntity>()
                .eq(RuntimeToolCallLogEntity::getTraceId, normalizedTraceId)
                .orderByAsc(RuntimeToolCallLogEntity::getId));
        List<RuntimeAgentTraceSpanEntity> spans = spanMapper.selectList(new LambdaQueryWrapper<RuntimeAgentTraceSpanEntity>()
                .eq(RuntimeAgentTraceSpanEntity::getTraceId, normalizedTraceId)
                .orderByAsc(RuntimeAgentTraceSpanEntity::getStartedAt)
                .orderByAsc(RuntimeAgentTraceSpanEntity::getId));
        if (logs.isEmpty() && spans.isEmpty()) {
            return Optional.empty();
        }
        List<RuntimeTraceNodeView> nodes = Stream.concat(
                        logs.stream().map(this::toNode),
                        spans.stream().map(this::toNode))
                .sorted(Comparator
                        .comparing(RuntimeTraceNodeView::createdAt, Comparator.nullsLast(LocalDateTime::compareTo))
                        .thenComparing(RuntimeTraceNodeView::id, Comparator.nullsLast(Long::compareTo)))
                .toList();
        return Optional.of(new RuntimeTraceDetailView(normalizedTraceId, nodes));
    }

    public List<RuntimeTraceSummaryView> listRecentTraces(String userId, int limit, int days) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
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
        List<RuntimeToolCallLogEntity> logs = toolLogMapper.selectList(wrapper);
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
                .map(entry -> toSummary(entry.getKey(), entry.getValue()))
                .toList();
    }

    private RuntimeTraceNodeView toNode(RuntimeToolCallLogEntity log) {
        return new RuntimeTraceNodeView(
                log.getId(),
                "runtime_tool_call_log",
                log.getTraceId(),
                log.getAgentName(),
                log.getToolName(),
                null,
                null,
                null,
                null,
                null,
                log.getArgsJson(),
                log.getResultSummary(),
                Boolean.TRUE.equals(log.getSuccess()),
                log.getErrorCode(),
                log.getElapsedMs(),
                log.getTokenCost(),
                parseRetrieval(log.getRetrievalTraceJson()),
                log.getCreateTime());
    }

    private RuntimeTraceNodeView toNode(RuntimeAgentTraceSpanEntity span) {
        return new RuntimeTraceNodeView(
                span.getId(),
                "runtime_agent_trace_span",
                span.getTraceId(),
                span.getAgentName(),
                traceSpanName(span),
                span.getSpanType(),
                span.getSpanId(),
                span.getParentSpanId(),
                span.getNodeId(),
                span.getRuntimeType(),
                span.getInputSummary(),
                span.getOutputSummary(),
                "SUCCESS".equalsIgnoreCase(span.getStatus()),
                span.getErrorCode(),
                span.getLatencyMs(),
                span.getTokenCost(),
                List.of(),
                span.getStartedAt() == null ? span.getCreatedAt() : span.getStartedAt());
    }

    private List<Map<String, Object>> parseRetrieval(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, CANDIDATES_TYPE);
        } catch (Exception ignored) {
            return List.of(Map.of("raw", raw));
        }
    }

    static String traceSpanName(RuntimeAgentTraceSpanEntity span) {
        if (StringUtils.hasText(span.getToolName())) {
            return "span:" + span.getToolName();
        }
        if (StringUtils.hasText(span.getNodeId())) {
            return "span:" + span.getNodeId();
        }
        return "span:" + span.getSpanType();
    }

    private RuntimeTraceSummaryView toSummary(String traceId, List<RuntimeToolCallLogEntity> traceLogs) {
        traceLogs.sort(Comparator.comparing(RuntimeToolCallLogEntity::getId));
        RuntimeToolCallLogEntity first = traceLogs.get(0);
        RuntimeToolCallLogEntity last = traceLogs.get(traceLogs.size() - 1);
        long successCount = traceLogs.stream()
                .filter(log -> Boolean.TRUE.equals(log.getSuccess()))
                .count();
        return new RuntimeTraceSummaryView(
                traceId,
                first.getSessionId(),
                first.getUserId(),
                first.getAgentName(),
                first.getIntentType(),
                traceLogs.size(),
                successCount,
                first.getCreateTime(),
                last.getCreateTime());
    }
}
