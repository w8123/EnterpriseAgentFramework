package com.enterprise.ai.runtime.debug;

import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Joins the debug owner's fenced transition; execution and external calls never run here. */
@Service
@RequiredArgsConstructor
public class RuntimeDebugExecutionLifecycle {
    private final RuntimeRunLifecycleService runs;
    private final RuntimeTraceSpanTerminationService spans;

    @Transactional(propagation = Propagation.MANDATORY)
    public void expire(String traceId, String code, String message, LocalDateTime expiredAt) {
        runs.timeoutDebugExecution(traceId, code, message, expiredAt);
        spans.timeoutOpen(traceId, code, message, expiredAt);
    }
}
