package com.enterprise.ai.runtime.trace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 终止指定 Trace 下尚未结束的 Span，保留已有终态证据。 */
@Service
@RequiredArgsConstructor
public class RuntimeTraceSpanTerminationService {
    private final RuntimeTraceSpanMapper spans;

    /** Close the earlier wait interval only when this exact Workflow node has a terminal result. */
    @Transactional
    public int finishWaitingWorkflowNode(String traceId, String parentSpanId, String nodeId,
                                         String status, String errorCode, LocalDateTime endedAt) {
        if (!StringUtils.hasText(traceId) || !StringUtils.hasText(parentSpanId) || !StringUtils.hasText(nodeId)) {
            throw new IllegalArgumentException("Workflow waiting-span completion requires trace, parent and node identity");
        }
        if (!StringUtils.hasText(status)
                || !Set.of("SUCCESS", "FAILED", "ERROR", "TIMEOUT", "CANCELLED", "SKIPPED", "BUSINESS_TERMINAL").contains(status)) {
            throw new IllegalArgumentException("Workflow waiting-span completion requires a terminal node status");
        }
        Objects.requireNonNull(endedAt, "endedAt");
        return spans.update(null, Wrappers.<RuntimeTraceSpanEntity>lambdaUpdate()
                .eq(RuntimeTraceSpanEntity::getTraceId, traceId)
                .eq(RuntimeTraceSpanEntity::getParentSpanId, parentSpanId)
                .eq(RuntimeTraceSpanEntity::getNodeId, nodeId)
                .eq(RuntimeTraceSpanEntity::getSpanType, "WORKFLOW_NODE")
                .in(RuntimeTraceSpanEntity::getStatus, List.of("WAITING_USER", "WAITING_APPROVAL"))
                .isNull(RuntimeTraceSpanEntity::getEndedAt)
                .le(RuntimeTraceSpanEntity::getStartedAt, endedAt)
                .set(RuntimeTraceSpanEntity::getStatus, status)
                .set(RuntimeTraceSpanEntity::getErrorCode, errorCode)
                .set(RuntimeTraceSpanEntity::getEndedAt, endedAt));
    }

    /** Called after the run owner has fenced an abandoned execution; existing terminal spans survive. */
    @Transactional
    public int failRunning(String traceId, String code, String message, LocalDateTime endedAt) {
        if (!StringUtils.hasText(traceId)) return 0;
        if (!StringUtils.hasText(code)) throw new IllegalArgumentException("Trace termination requires an error code");
        Objects.requireNonNull(endedAt, "endedAt");
        return spans.update(null, Wrappers.<RuntimeTraceSpanEntity>lambdaUpdate()
                .eq(RuntimeTraceSpanEntity::getTraceId, traceId.trim())
                .eq(RuntimeTraceSpanEntity::getStatus, "RUNNING")
                .isNull(RuntimeTraceSpanEntity::getEndedAt)
                .set(RuntimeTraceSpanEntity::getStatus, "ERROR")
                .set(RuntimeTraceSpanEntity::getErrorCode, code)
                .set(RuntimeTraceSpanEntity::getErrorMessage, message)
                .set(RuntimeTraceSpanEntity::getEndedAt, endedAt));
    }

    @Transactional
    public int finishWaiting(String traceId, String code, String message, LocalDateTime endedAt) {
        return finishWaiting(traceId, "ERROR", code, message, endedAt);
    }

    @Transactional
    public int expireWaiting(String traceId, String interactionId, LocalDateTime endedAt) {
        return finishWaiting(traceId, "TIMEOUT", "RUNTIME_INTERACTION_EXPIRED",
                "Interaction expired: " + interactionId, endedAt);
    }

    @Transactional
    public int timeoutWaiting(String traceId, String code, String message, LocalDateTime endedAt) {
        return finishWaiting(traceId, "TIMEOUT", code, message, endedAt);
    }

    /** The interaction owner has fenced a timed-out resume; preserve all existing terminal evidence. */
    @Transactional
    public int timeoutResuming(String traceId, String code, String message, LocalDateTime endedAt) {
        return timeoutOpen(traceId, code, message, endedAt);
    }

    /** The execution owner has committed its timeout fence in the caller's transaction. */
    @Transactional
    public int timeoutOpen(String traceId, String code, String message, LocalDateTime endedAt) {
        return finishOpen(traceId, "TIMEOUT", code, message, endedAt,
                List.of("RUNNING", "WAITING_USER", "WAITING_APPROVAL"));
    }

    private int finishWaiting(String traceId, String status, String code, String message, LocalDateTime endedAt) {
        return finishOpen(traceId, status, code, message, endedAt, List.of("WAITING_USER", "WAITING_APPROVAL"));
    }

    private int finishOpen(String traceId, String status, String code, String message, LocalDateTime endedAt,
                           List<String> openStatuses) {
        if (!StringUtils.hasText(traceId)) {
            return 0;
        }
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("Trace termination requires an error code");
        }
        Objects.requireNonNull(endedAt, "endedAt");
        return spans.update(null, Wrappers.<RuntimeTraceSpanEntity>lambdaUpdate()
                .eq(RuntimeTraceSpanEntity::getTraceId, traceId.trim())
                .in(RuntimeTraceSpanEntity::getStatus, openStatuses)
                .isNull(RuntimeTraceSpanEntity::getEndedAt)
                .set(RuntimeTraceSpanEntity::getStatus, status)
                .set(RuntimeTraceSpanEntity::getErrorCode, code)
                .set(RuntimeTraceSpanEntity::getErrorMessage, message)
                .set(RuntimeTraceSpanEntity::getEndedAt, endedAt));
    }
}
