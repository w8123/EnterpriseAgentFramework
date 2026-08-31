package com.enterprise.ai.control.a2a.application.task;

import com.enterprise.ai.control.a2a.application.identity.A2aInboundCallContext;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.TaskRecord;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/** Implements A2A 1.0's blocking-by-default SendMessage behavior using the shared DB projection. */
@Service
@RequiredArgsConstructor
public class A2aTaskCompletionWaiter {

    private final A2aTaskRepository repository;
    private final A2aTaskDispatchService dispatchService;
    private final Clock clock;

    public void await(A2aInboundCallContext call, String taskId) {
        while (true) {
            TaskRecord task = repository.findTask(
                            A2aDirection.INBOUND, call.principal().principalId(),
                            tenant(call), taskId)
                    .filter(value -> value.publicationId() != null
                            && value.publicationId().longValue() == call.publication().publicationId())
                    .orElseThrow(() -> new A2aDomainException(
                            "A2A_TASK_NOT_FOUND", "the specified task is not accessible"));
            if (task.state().terminal() || task.state().interrupted()) {
                return;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            if (task.deadlineAt() != null && !task.deadlineAt().isAfter(now)) {
                dispatchService.expireTask(task.executionId());
                continue;
            }
            long sleepMs = 100L;
            if (task.deadlineAt() != null) {
                sleepMs = Math.max(1L, Math.min(sleepMs,
                        Duration.between(now, task.deadlineAt()).toMillis()));
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new A2aDomainException(
                        "A2A_BLOCKING_WAIT_INTERRUPTED", "blocking SendMessage wait was interrupted");
            }
        }
    }

    private String tenant(A2aInboundCallContext call) {
        return call.principal().tenantScope() == null ? "" : call.principal().tenantScope().trim();
    }
}
