package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

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

    private final RuntimeWorkflowInteractionSessionService sessions;
    private final RuntimeTraceSpanTerminationService traces;

    @Transactional
    void terminate(String interactionId, String traceId, WorkflowExecutionIdentity identity) {
        if (!StringUtils.hasText(traceId)) {
            return;
        }
        // Interaction sessions and trace spans use the application's local wall clock.
        // Automation lease timestamps are UTC, but must not change this owning domain's clock.
        LocalDateTime now = LocalDateTime.now();
        sessions.cancelWaiting(new RuntimeWorkflowInteractionSessionService.Cancellation(
                interactionId, traceId, TERMINAL_CODE, "AUTOMATION_FAIL_CLOSED",
                identity == null ? "automation-runtime" : identity.userId(), now));
        traces.finishWaiting(traceId, TERMINAL_CODE, TERMINAL_MESSAGE, now);
    }
}
