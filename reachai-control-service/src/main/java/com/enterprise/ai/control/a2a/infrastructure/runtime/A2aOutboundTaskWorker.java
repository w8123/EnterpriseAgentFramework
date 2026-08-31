package com.enterprise.ai.control.a2a.infrastructure.runtime;

import com.enterprise.ai.control.a2a.application.outbound.A2aOutboundDelegationService;
import com.enterprise.ai.control.a2a.application.port.A2aOutboundExecutionRepository;
import com.enterprise.ai.control.a2a.application.port.A2aOutboundExecutionRepository.OutboundExecution;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/** Distributed, leased HTTP+JSON Task polling and deadline convergence for outbound Tasks. */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "reachai.a2a-hub", name = "enabled", havingValue = "true")
public class A2aOutboundTaskWorker {

    private final A2aOutboundExecutionRepository executions;
    private final A2aTaskRepository tasks;
    private final A2aOutboundDelegationService delegation;
    private final A2aHubProperties properties;
    private final Clock clock;
    private final ExecutorService executor;
    private final Semaphore capacity;
    private final String workerId = "a2a-poll-" + UUID.randomUUID();

    public A2aOutboundTaskWorker(
            A2aOutboundExecutionRepository executions,
            A2aTaskRepository tasks,
            A2aOutboundDelegationService delegation,
            A2aHubProperties properties,
            Clock clock,
            @Qualifier("a2aOutboundPollExecutor") ExecutorService executor) {
        this.executions = executions;
        this.tasks = tasks;
        this.delegation = delegation;
        this.properties = properties;
        this.clock = clock;
        this.executor = executor;
        this.capacity = new Semaphore(Math.max(1,
                Math.min(properties.getOutbound().getPollWorkers(), 32)));
    }

    @Scheduled(
            fixedDelayString = "${reachai.a2a-hub.outbound.poll-scan-delay:1s}",
            initialDelayString = "${reachai.a2a-hub.outbound.initial-poll-delay:2s}")
    public void pollDueTasks() {
        int requested = Math.min(Math.max(1,
                properties.getOutbound().getPollBatchSize()), capacity.availablePermits());
        if (requested <= 0) return;
        int reserved = 0;
        while (reserved < requested && capacity.tryAcquire()) reserved++;
        if (reserved == 0) return;
        LocalDateTime now = now();
        List<OutboundExecution> claimed;
        try {
            claimed = executions.claimDue(workerId, now, now.plus(pollLease()), reserved);
        } catch (RuntimeException failure) {
            capacity.release(reserved);
            log.warn("[A2AOutboundPoll] claim failed: {}", safe(failure.getMessage()));
            return;
        }
        if (claimed.size() < reserved) capacity.release(reserved - claimed.size());
        for (OutboundExecution execution : claimed) {
            executor.execute(() -> {
                try {
                    delegation.poll(execution.taskRefId(), workerId);
                } catch (RuntimeException failure) {
                    log.warn("[A2AOutboundPoll] Task ref {} could not converge: {}",
                            execution.taskRefId(), safe(failure.getMessage()));
                } finally {
                    capacity.release();
                }
            });
        }
    }

    @Scheduled(fixedDelayString = "${reachai.a2a-hub.deadline-scan-delay:5s}")
    public void expireDueTasks() {
        for (String executionId : tasks.findDueOutboundExecutionIds(now(), 100)) {
            try {
                delegation.expire(executionId);
            } catch (RuntimeException failure) {
                log.warn("[A2AOutboundDeadline] could not expire {}: {}",
                        executionId, safe(failure.getMessage()));
            }
        }
    }

    private Duration pollLease() {
        Duration configured = properties.getOutbound().getPollLease();
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofSeconds(30) : configured;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "outbound Task processing failed";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }
}
