package com.enterprise.ai.agent.trace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTraceSpanService {

    private static final int SUMMARY_LIMIT = 4000;
    private static final int METADATA_LIMIT = 16000;

    private final AgentTraceSpanMapper mapper;
    private final ObjectMapper objectMapper;

    public List<AgentTraceSpanEntity> listByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<AgentTraceSpanEntity>()
                .eq(AgentTraceSpanEntity::getTraceId, traceId.trim())
                .orderByAsc(AgentTraceSpanEntity::getStartedAt)
                .orderByAsc(AgentTraceSpanEntity::getId));
    }

    public void record(ToolExecutionContext context, AgentDefinition definition, SpanRecord record) {
        if (record == null) {
            return;
        }
        AgentTraceSpanEntity entity = new AgentTraceSpanEntity();
        entity.setTraceId(firstNonBlank(record.traceId(), context == null ? null : context.getTraceId()));
        entity.setSpanId(firstNonBlank(record.spanId(), newSpanId(record.spanType())));
        entity.setParentSpanId(record.parentSpanId());
        entity.setSpanType(firstNonBlank(record.spanType(), "UNKNOWN"));
        entity.setRuntimeType(record.runtimeType());
        entity.setAgentId(definition == null ? null : definition.getId());
        entity.setAgentName(definition == null ? context == null ? null : context.getAgentName() : definition.getName());
        entity.setNodeId(record.nodeId());
        entity.setToolName(record.toolName());
        entity.setModelInstanceId(record.modelInstanceId());
        entity.setProjectCode(definition == null ? context == null ? null : context.getProjectCode() : definition.getProjectCode());
        entity.setTenantId(context == null ? null : context.getTenantId());
        entity.setAppId(context == null ? null : context.getAppId());
        entity.setExternalUserId(context == null ? null : context.getExternalUserId());
        entity.setGlobalUserId(context == null ? null : context.getGlobalUserId());
        entity.setPageInstanceId(context == null ? null : context.getPageInstanceId());
        entity.setStatus(firstNonBlank(record.status(), record.success() ? "SUCCESS" : "ERROR"));
        entity.setInputSummary(truncate(stringify(record.input()), SUMMARY_LIMIT));
        entity.setOutputSummary(truncate(stringify(record.output()), SUMMARY_LIMIT));
        entity.setMetadataJson(truncate(toJson(record.metadata()), METADATA_LIMIT));
        entity.setErrorCode(record.errorCode());
        entity.setErrorMessage(truncate(record.errorMessage(), SUMMARY_LIMIT));
        entity.setLatencyMs(toInt(record.latencyMs()));
        entity.setTokenCost(record.tokenCost());
        entity.setStartedAt(record.startedAt() == null ? LocalDateTime.now() : record.startedAt());
        entity.setEndedAt(record.endedAt() == null ? LocalDateTime.now() : record.endedAt());
        entity.setCreatedAt(LocalDateTime.now());

        try {
            mapper.insert(entity);
        } catch (Exception ex) {
            log.warn("[AgentTraceSpan] write failed: traceId={}, spanType={}, err={}",
                    entity.getTraceId(), entity.getSpanType(), ex.toString());
        }
    }

    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence s) {
            return s.toString();
        }
        return toJson(value);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "...[truncated]";
    }

    private static Integer toInt(long value) {
        return (int) Math.min(Math.max(value, 0), Integer.MAX_VALUE);
    }

    private static String newSpanId(String spanType) {
        String prefix = firstNonBlank(spanType, "span").toLowerCase().replaceAll("[^a-z0-9]+", "-");
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    @Builder
    public record SpanRecord(
            String traceId,
            String spanId,
            String parentSpanId,
            String spanType,
            String runtimeType,
            String nodeId,
            String toolName,
            String modelInstanceId,
            Object input,
            Object output,
            String status,
            boolean success,
            String errorCode,
            String errorMessage,
            long latencyMs,
            Integer tokenCost,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            Map<String, Object> metadata
    ) {
    }
}
