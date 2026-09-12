package com.enterprise.ai.runtime.trace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class RuntimeTraceQueryService implements RuntimeTraceRecordQuery {

    private static final TypeReference<List<Map<String, Object>>> CANDIDATES_TYPE = new TypeReference<>() {
    };

    private final RuntimeToolCallLogMapper toolLogMapper;
    private final RuntimeTraceSpanMapper spanMapper;
    private final ObjectMapper objectMapper;

    @Override
    public RuntimeTraceRecords findRecords(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return new RuntimeTraceRecords(List.of(), List.of());
        }
        String normalizedTraceId = traceId.trim();
        List<RuntimeToolCallLogEntity> logs = toolLogMapper.selectList(new LambdaQueryWrapper<RuntimeToolCallLogEntity>()
                .eq(RuntimeToolCallLogEntity::getTraceId, normalizedTraceId)
                .orderByAsc(RuntimeToolCallLogEntity::getCreateTime)
                .orderByAsc(RuntimeToolCallLogEntity::getId));
        return new RuntimeTraceRecords(
                findSpans(normalizedTraceId),
                logs.stream().map(this::toRecord).toList());
    }

    @Override
    public Map<String, RuntimeTraceRecords> findRecordsByTraceIds(List<String> traceIds) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (traceIds != null) {
            for (String traceId : traceIds) {
                if (StringUtils.hasText(traceId)) ids.add(traceId.trim());
                if (ids.size() > MAX_BATCH_TRACE_IDS) {
                    throw new IllegalArgumentException("Trace record batch exceeds " + MAX_BATCH_TRACE_IDS + " distinct IDs");
                }
            }
        }
        if (ids.isEmpty()) return Map.of();
        Map<String, List<RuntimeTraceRecords.Span>> spans = new LinkedHashMap<>();
        Map<String, List<RuntimeTraceRecords.ToolCall>> calls = new LinkedHashMap<>();
        ids.forEach(id -> {
            spans.put(id, new ArrayList<>());
            calls.put(id, new ArrayList<>());
        });
        List<RuntimeTraceSpanEntity> spanRows = spanMapper.selectList(new LambdaQueryWrapper<RuntimeTraceSpanEntity>()
                .in(RuntimeTraceSpanEntity::getTraceId, ids)
                .orderByAsc(RuntimeTraceSpanEntity::getStartedAt)
                .orderByAsc(RuntimeTraceSpanEntity::getId));
        for (RuntimeTraceSpanEntity row : spanRows) {
            List<RuntimeTraceRecords.Span> target = spans.get(row.getTraceId());
            if (target != null) target.add(toRecord(row));
        }
        List<RuntimeToolCallLogEntity> callRows = toolLogMapper.selectList(new LambdaQueryWrapper<RuntimeToolCallLogEntity>()
                .in(RuntimeToolCallLogEntity::getTraceId, ids)
                .orderByAsc(RuntimeToolCallLogEntity::getCreateTime)
                .orderByAsc(RuntimeToolCallLogEntity::getId));
        for (RuntimeToolCallLogEntity row : callRows) {
            List<RuntimeTraceRecords.ToolCall> target = calls.get(row.getTraceId());
            if (target != null) target.add(toRecord(row));
        }
        Map<String, RuntimeTraceRecords> records = new LinkedHashMap<>();
        ids.forEach(id -> records.put(id, new RuntimeTraceRecords(spans.get(id), calls.get(id))));
        return Collections.unmodifiableMap(records);
    }

    @Override
    public List<RuntimeTraceRecords.Span> findSpans(String traceId) {
        if (!StringUtils.hasText(traceId)) return List.of();
        return spanMapper.selectList(new LambdaQueryWrapper<RuntimeTraceSpanEntity>()
                        .eq(RuntimeTraceSpanEntity::getTraceId, traceId.trim())
                        .orderByAsc(RuntimeTraceSpanEntity::getStartedAt)
                        .orderByAsc(RuntimeTraceSpanEntity::getId))
                .stream().map(this::toRecord).toList();
    }

    public Optional<RuntimeTraceDetailView> getTraceDetail(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return Optional.empty();
        }
        String normalizedTraceId = traceId.trim();
        RuntimeTraceRecords records = findRecords(normalizedTraceId);
        List<RuntimeTraceRecords.ToolCall> logs = records.toolCalls();
        List<RuntimeTraceRecords.Span> spans = records.spans();
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

    private RuntimeTraceRecords.Span toRecord(RuntimeTraceSpanEntity span) {
        return new RuntimeTraceRecords.Span(
                span.getId(), span.getTraceId(), span.getAgentName(), span.getSpanId(), span.getParentSpanId(),
                span.getSpanType(), span.getRuntimeType(), span.getNodeId(), span.getToolName(), span.getStatus(),
                span.getInputSummary(), span.getOutputSummary(), span.getMetadataJson(), span.getErrorCode(),
                span.getErrorMessage(), span.getLatencyMs(), span.getTokenCost(), span.getStartedAt(), span.getEndedAt(),
                span.getCreatedAt());
    }

    private RuntimeTraceRecords.ToolCall toRecord(RuntimeToolCallLogEntity log) {
        return new RuntimeTraceRecords.ToolCall(
                log.getId(), log.getTraceId(), log.getToolName(), log.getAgentName(), log.getSessionId(), log.getUserId(),
                log.getIntentType(), log.getProjectCode(), log.getSuccess(), log.getArgsJson(), log.getResultSummary(),
                log.getErrorCode(), log.getElapsedMs(), log.getTokenCost(), log.getRetrievalTraceJson(), log.getCreateTime());
    }

    private RuntimeTraceNodeView toNode(RuntimeTraceRecords.ToolCall log) {
        return new RuntimeTraceNodeView(
                log.id(),
                "runtime_tool_call_log",
                log.traceId(),
                log.agentName(),
                log.toolName(),
                null,
                null,
                null,
                null,
                null,
                log.argsJson(),
                log.resultSummary(),
                Boolean.TRUE.equals(log.success()),
                log.errorCode(),
                log.elapsedMs(),
                log.tokenCost(),
                parseRetrieval(log.retrievalTraceJson()),
                log.createTime());
    }

    private RuntimeTraceNodeView toNode(RuntimeTraceRecords.Span span) {
        return new RuntimeTraceNodeView(
                span.id(),
                "runtime_trace_span",
                span.traceId(),
                span.agentName(),
                traceSpanName(span),
                span.spanType(),
                span.spanId(),
                span.parentSpanId(),
                span.nodeId(),
                span.runtimeType(),
                span.inputSummary(),
                span.outputSummary(),
                "SUCCESS".equalsIgnoreCase(span.status()),
                span.errorCode(),
                span.latencyMs(),
                span.tokenCost(),
                List.of(),
                span.startedAt() == null ? span.createdAt() : span.startedAt());
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

    static String traceSpanName(RuntimeTraceRecords.Span span) {
        if (StringUtils.hasText(span.toolName())) {
            return "span:" + span.toolName();
        }
        if (StringUtils.hasText(span.nodeId())) {
            return "span:" + span.nodeId();
        }
        return "span:" + span.spanType();
    }

}
