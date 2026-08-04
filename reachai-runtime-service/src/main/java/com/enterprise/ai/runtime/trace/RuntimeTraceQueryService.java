package com.enterprise.ai.runtime.trace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
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
    private final RuntimeTraceSpanMapper spanMapper;
    private final RuntimeRunMapper runMapper;
    private final ObjectMapper objectMapper;

    public Optional<RuntimeTraceDetailView> getTraceDetail(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return Optional.empty();
        }
        String normalizedTraceId = traceId.trim();
        List<RuntimeToolCallLogEntity> logs = toolLogMapper.selectList(new LambdaQueryWrapper<RuntimeToolCallLogEntity>()
                .eq(RuntimeToolCallLogEntity::getTraceId, normalizedTraceId)
                .orderByAsc(RuntimeToolCallLogEntity::getId));
        List<RuntimeTraceSpanEntity> spans = spanMapper.selectList(new LambdaQueryWrapper<RuntimeTraceSpanEntity>()
                .eq(RuntimeTraceSpanEntity::getTraceId, normalizedTraceId)
                .orderByAsc(RuntimeTraceSpanEntity::getStartedAt)
                .orderByAsc(RuntimeTraceSpanEntity::getId));
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
        LambdaQueryWrapper<RuntimeRunEntity> wrapper = new LambdaQueryWrapper<RuntimeRunEntity>()
                .ge(RuntimeRunEntity::getStartedAt, LocalDateTime.now().minusDays(safeDays))
                .orderByDesc(RuntimeRunEntity::getStartedAt)
                .orderByDesc(RuntimeRunEntity::getId)
                .last("limit " + safeLimit);
        if (StringUtils.hasText(userId)) {
            wrapper.eq(RuntimeRunEntity::getUserId, userId.trim());
        }
        return runMapper.selectList(wrapper).stream()
                .map(this::toSummary)
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

    private RuntimeTraceNodeView toNode(RuntimeTraceSpanEntity span) {
        return new RuntimeTraceNodeView(
                span.getId(),
                "runtime_trace_span",
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

    static String traceSpanName(RuntimeTraceSpanEntity span) {
        if (StringUtils.hasText(span.getToolName())) {
            return "span:" + span.getToolName();
        }
        if (StringUtils.hasText(span.getNodeId())) {
            return "span:" + span.getNodeId();
        }
        return "span:" + span.getSpanType();
    }

    private RuntimeTraceSummaryView toSummary(RuntimeRunEntity run) {
        return new RuntimeTraceSummaryView(
                run.getTraceId(),
                run.getSessionId(),
                run.getUserId(),
                firstText(run.getAgentName(), run.getWorkflowName()),
                run.getEntryType(),
                run.getToolCallCount() == null ? 0 : run.getToolCallCount(),
                RuntimeRunStatus.parse(run.getStatus()) == RuntimeRunStatus.COMPLETED ? 1L : 0L,
                run.getStartedAt(),
                run.getEndedAt());
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }
}
