package com.enterprise.ai.agent.tool.log;

import com.enterprise.ai.agent.config.ToolCallLogProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Tool 调用审计日志写入服务。
 * <p>
 * 调用方埋点一次 {@link #record(ToolExecutionContext, String, java.util.Map, Object, boolean, String, long)}，
 * 根据配置同步或异步写入 {@code tool_call_log}；任何写入异常都吞掉（只打日志），不拖累 Agent 主链路。
 */
@Slf4j
@Service
public class ToolCallLogService {

    private final ToolCallLogMapper mapper;
    private final ToolCallLogProperties properties;
    private final ObjectMapper objectMapper;

    public ToolCallLogService(ToolCallLogMapper mapper,
                              ToolCallLogProperties properties,
                              ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录一次 tool 调用。
     */
    public void record(ToolExecutionContext context,
                       String toolName,
                       Map<String, Object> args,
                       Object result,
                       boolean success,
                       String errorCode,
                       long elapsedMs) {
        if (!properties.isEnabled()) {
            return;
        }
        ToolCallLogEntity entity = new ToolCallLogEntity();
        entity.setTraceId(context == null ? null : context.getTraceId());
        entity.setSessionId(context == null ? null : context.getSessionId());
        entity.setUserId(context == null ? null : context.getUserId());
        entity.setAgentName(context == null ? null : context.getAgentName());
        entity.setIntentType(context == null ? null : context.getIntentType());
        entity.setToolName(toolName);
        entity.setArgsJson(truncate(toJson(args), properties.getArgsMaxChars()));
        entity.setResultSummary(truncate(stringify(result), properties.getResultMaxChars()));
        entity.setSuccess(success);
        entity.setErrorCode(errorCode);
        entity.setElapsedMs((int) Math.min(elapsedMs, Integer.MAX_VALUE));
        entity.setRetrievalTraceJson(context == null ? null : context.getRetrievalTraceJson());
        entity.setCreateTime(LocalDateTime.now());

        if (properties.isAsync()) {
            persistAsync(entity);
        } else {
            persist(entity);
        }
    }

    @Async
    public void persistAsync(ToolCallLogEntity entity) {
        persist(entity);
    }

    private void persist(ToolCallLogEntity entity) {
        try {
            mapper.insert(entity);
        } catch (Exception ex) {
            log.warn("[ToolCallLog] 写入失败: toolName={}, err={}", entity.getToolName(), ex.toString());
        }
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

    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence s) {
            return s.toString();
        }
        return toJson(value);
    }

    private String truncate(String s, int max) {
        if (s == null || max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...[truncated]";
    }
}
