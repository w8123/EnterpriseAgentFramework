package com.enterprise.ai.runtime.automation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.execution.RuntimeInteractionEventEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionEventMapper;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionMapper;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Fail-closed cleanup for an interaction that escaped a scheduled execution boundary.
 *
 * <p>The execution kernel rejects blocking interaction nodes for an Automation identity before a
 * session is created. This service is the second line of defence for Supervisor approvals and for
 * legacy/custom adapters: it conditionally cancels only a WAITING_USER session belonging to the
 * same trace, then closes every waiting span. The caller remains responsible for finishing the
 * owning RunOps row with the Automation terminal result.</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "reachai.runtime.automation.enabled", havingValue = "true")
class RuntimeAutomationInteractionTerminationService {

    private static final String TERMINAL_CODE = "AUTOMATION_INTERACTION_REQUIRED";
    private static final String TERMINAL_MESSAGE =
            "Scheduled execution requires human interaction and was stopped fail-closed";

    private final RuntimeInteractionSessionMapper sessionMapper;
    private final RuntimeInteractionEventMapper eventMapper;
    private final RuntimeTraceSpanMapper traceSpanMapper;
    private final RuntimeAutomationJsonSupport json;

    @Transactional
    void terminate(String interactionId, String traceId, WorkflowExecutionIdentity identity) {
        if (!StringUtils.hasText(traceId)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        if (StringUtils.hasText(interactionId)) {
            RuntimeInteractionSessionEntity session = sessionMapper.selectById(interactionId.trim());
            if (session != null && traceId.equals(session.getTraceId())) {
                int updated = sessionMapper.update(null,
                        Wrappers.<RuntimeInteractionSessionEntity>lambdaUpdate()
                                .eq(RuntimeInteractionSessionEntity::getId, session.getId())
                                .eq(RuntimeInteractionSessionEntity::getTraceId, traceId)
                                .eq(RuntimeInteractionSessionEntity::getStatus, "WAITING_USER")
                                .set(RuntimeInteractionSessionEntity::getStatus, "CANCELLED")
                                .set(RuntimeInteractionSessionEntity::getRevision,
                                        (session.getRevision() == null ? 0 : session.getRevision()) + 1)
                                .set(RuntimeInteractionSessionEntity::getResultJson, json.write(Map.of(
                                        "code", TERMINAL_CODE,
                                        "status", "CANCELLED",
                                        "reason", "AUTOMATION_FAIL_CLOSED")))
                                .set(RuntimeInteractionSessionEntity::getUpdateTime, now));
                if (updated == 1) {
                    RuntimeInteractionEventEntity event = new RuntimeInteractionEventEntity();
                    event.setSessionId(session.getId());
                    event.setEventType("CANCELLED");
                    event.setPayloadJson(json.write(Map.of(
                            "code", TERMINAL_CODE,
                            "reason", "AUTOMATION_FAIL_CLOSED",
                            "traceId", traceId)));
                    event.setOperatorId(identity == null ? "automation-runtime" : identity.userId());
                    event.setCreateTime(now);
                    eventMapper.insert(event);
                }
            }
        }
        traceSpanMapper.update(null, Wrappers.<RuntimeTraceSpanEntity>lambdaUpdate()
                .eq(RuntimeTraceSpanEntity::getTraceId, traceId)
                .in(RuntimeTraceSpanEntity::getStatus, List.of("WAITING_USER", "WAITING_APPROVAL"))
                .isNull(RuntimeTraceSpanEntity::getEndedAt)
                .set(RuntimeTraceSpanEntity::getStatus, "ERROR")
                .set(RuntimeTraceSpanEntity::getErrorCode, TERMINAL_CODE)
                .set(RuntimeTraceSpanEntity::getErrorMessage, TERMINAL_MESSAGE)
                .set(RuntimeTraceSpanEntity::getEndedAt, now));
    }
}
