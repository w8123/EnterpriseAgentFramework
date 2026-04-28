package com.enterprise.ai.agent.a2a;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * A2A 调用审计：异步落库，不阻塞调用主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class A2aCallLogService {

    private final A2aCallLogMapper logMapper;

    @Async
    public void log(Long endpointId, String agentKey, String taskId, String method,
                    boolean success, long latencyMs, String requestBody, String responseBody,
                    String errorMessage, String traceId, String remoteIp) {
        try {
            A2aCallLogEntity en = new A2aCallLogEntity();
            en.setEndpointId(endpointId);
            en.setAgentKey(agentKey);
            en.setTaskId(taskId);
            en.setMethod(method);
            en.setSuccess(success);
            en.setLatencyMs(latencyMs);
            en.setRequestBody(truncate(requestBody, 8000));
            en.setResponseBody(truncate(responseBody, 8000));
            en.setErrorMessage(truncate(errorMessage, 1900));
            en.setTraceId(traceId);
            en.setRemoteIp(remoteIp);
            en.setCreatedAt(LocalDateTime.now());
            logMapper.insert(en);
        } catch (Exception e) {
            log.warn("[A2aCallLog] 写日志失败: {}", e.getMessage());
        }
    }

    public Page<A2aCallLogEntity> page(int pageNum, int pageSize, String agentKey, String method, Boolean success) {
        Page<A2aCallLogEntity> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<A2aCallLogEntity> qw = new LambdaQueryWrapper<>();
        if (agentKey != null && !agentKey.isBlank()) {
            qw.eq(A2aCallLogEntity::getAgentKey, agentKey);
        }
        if (method != null && !method.isBlank()) {
            qw.eq(A2aCallLogEntity::getMethod, method);
        }
        if (success != null) {
            qw.eq(A2aCallLogEntity::getSuccess, success);
        }
        qw.orderByDesc(A2aCallLogEntity::getId);
        return logMapper.selectPage(page, qw);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
