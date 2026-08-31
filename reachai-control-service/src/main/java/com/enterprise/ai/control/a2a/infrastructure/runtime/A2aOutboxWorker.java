package com.enterprise.ai.control.a2a.infrastructure.runtime;

import com.enterprise.ai.control.a2a.application.port.A2aOutboxRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRuntimeExecutionGateway;
import com.enterprise.ai.control.a2a.application.port.A2aRuntimeExecutionGateway.CallException;
import com.enterprise.ai.control.a2a.application.port.A2aRuntimeExecutionGateway.ExecutionResult;
import com.enterprise.ai.control.a2a.application.port.A2aRuntimeExecutionGateway.FailureKind;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.OutboxRecord;
import com.enterprise.ai.control.a2a.application.task.A2aTaskDispatchService;
import com.enterprise.ai.control.a2a.application.task.A2aTaskDispatchService.CancelPreparation;
import com.enterprise.ai.control.a2a.application.task.A2aTaskDispatchService.DispatchPreparation;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/** Durable A2A Task outbox dispatcher with bounded concurrency and lease heartbeats. */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "reachai.a2a-hub", name = "enabled", havingValue = "true")
public class A2aOutboxWorker {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final A2aOutboxRepository outbox;
    private final A2aTaskDispatchService tasks;
    private final A2aRuntimeExecutionGateway runtime;
    private final A2aHubProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ExecutorService executor;
    private final Semaphore capacity;
    private final String workerId = "a2a-" + UUID.randomUUID();
    private final Map<Long, ActiveLease> activeLeases = new ConcurrentHashMap<>();

    public A2aOutboxWorker(
            A2aOutboxRepository outbox,
            A2aTaskDispatchService tasks,
            A2aRuntimeExecutionGateway runtime,
            A2aHubProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            @Qualifier("a2aOutboxExecutor") ExecutorService executor) {
        this.outbox = outbox;
        this.tasks = tasks;
        this.runtime = runtime;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.executor = executor;
        int workers = Math.max(1, Math.min(properties.getOutboxWorkers(), 32));
        this.capacity = new Semaphore(workers);
    }

    @Scheduled(
            fixedDelayString = "${reachai.a2a-hub.outbox-delay:1s}",
            initialDelayString = "${reachai.a2a-hub.outbox-delay:1s}")
    public void poll() {
        recoverExpiredClaims();
        int requested = Math.min(
                Math.max(1, properties.getOutboxBatchSize()), capacity.availablePermits());
        if (requested <= 0) {
            return;
        }
        int reserved = 0;
        while (reserved < requested && capacity.tryAcquire()) {
            reserved++;
        }
        if (reserved == 0) {
            return;
        }
        LocalDateTime now = now();
        List<OutboxRecord> claimed;
        try {
            claimed = outbox.claimDue(workerId, now, now.plus(properties.getOutboxLease()), reserved);
        } catch (RuntimeException claimFailure) {
            capacity.release(reserved);
            log.warn("[A2AOutbox] claim failed: {}", safe(claimFailure.getMessage()));
            return;
        }
        if (claimed.size() < reserved) {
            capacity.release(reserved - claimed.size());
        }
        for (OutboxRecord record : claimed) {
            activeLeases.put(record.id(), new ActiveLease(record.id(), workerId));
            executor.execute(() -> {
                try {
                    process(record);
                } finally {
                    activeLeases.remove(record.id());
                    capacity.release();
                }
            });
        }
    }

    @Scheduled(fixedDelayString = "${reachai.a2a-hub.outbox-heartbeat:30s}")
    public void renewActiveLeases() {
        LocalDateTime leaseUntil = now().plus(properties.getOutboxLease());
        activeLeases.values().forEach(lease -> {
            try {
                if (!outbox.renewLease(lease.id(), lease.workerId(), leaseUntil)) {
                    log.warn("[A2AOutbox] lease lost for outbox {}", lease.id());
                }
            } catch (RuntimeException renewalFailure) {
                log.warn("[A2AOutbox] lease renewal failed for {}: {}",
                        lease.id(), safe(renewalFailure.getMessage()));
            }
        });
    }

    private void process(OutboxRecord record) {
        String executionId;
        try {
            executionId = executionId(record);
        } catch (RuntimeException malformed) {
            dead(record, record.attemptCount() + 1,
                    "A2A_OUTBOX_REFERENCE_INVALID", "Outbox resource reference is invalid");
            return;
        }
        if (isCancel(record.eventType())) {
            processCancel(record, executionId);
        } else if (isDispatch(record.eventType())) {
            processDispatch(record, executionId);
        } else {
            dead(record, record.attemptCount() + 1,
                    "A2A_OUTBOX_EVENT_UNSUPPORTED", "Outbox event type is unsupported");
        }
    }

    private void processDispatch(OutboxRecord record, String executionId) {
        DispatchPreparation preparation;
        try {
            preparation = tasks.prepareDispatch(executionId);
            if (!preparation.executable()) {
                delivered(record);
                return;
            }
        } catch (A2aDomainException permanent) {
            dead(record, record.attemptCount() + 1, permanent.code(), permanent.getMessage());
            return;
        } catch (RuntimeException beforeCall) {
            retryBeforeCall(record, executionId, false,
                    "A2A_DISPATCH_PREPARATION_FAILED", beforeCall);
            return;
        }

        ExecutionResult result;
        try {
            result = runtime.execute(preparation.command());
        } catch (CallException runtimeFailure) {
            handleRuntimeFailure(record, executionId, false, runtimeFailure);
            return;
        } catch (RuntimeException unknown) {
            markUnknown(record, executionId, false,
                    "A2A_DISPATCH_OUTCOME_UNKNOWN", unknown.getMessage());
            return;
        }

        try {
            tasks.applyExecutionResult(executionId, result);
            delivered(record);
        } catch (A2aDomainException invalidResult) {
            try {
                tasks.failDispatch(executionId, invalidResult.code(), invalidResult.getMessage());
            } catch (RuntimeException projectionFailure) {
                log.warn("[A2AOutbox] could not project invalid Runtime result for {}: {}",
                        executionId, safe(projectionFailure.getMessage()));
            }
            dead(record, record.attemptCount() + 1,
                    invalidResult.code(), invalidResult.getMessage());
        } catch (RuntimeException projectionFailure) {
            markUnknown(record, executionId, false,
                    "A2A_RESULT_PROJECTION_OUTCOME_UNKNOWN", projectionFailure.getMessage());
        }
    }

    private void processCancel(OutboxRecord record, String executionId) {
        CancelPreparation preparation;
        try {
            preparation = tasks.prepareCancel(
                    executionId, "EXECUTION_TIMEOUT_CANCEL_REQUESTED".equals(record.eventType()));
            if (!preparation.executable()) {
                delivered(record);
                return;
            }
        } catch (A2aDomainException permanent) {
            dead(record, record.attemptCount() + 1, permanent.code(), permanent.getMessage());
            return;
        } catch (RuntimeException beforeCall) {
            retryBeforeCall(record, executionId, true,
                    "A2A_CANCEL_PREPARATION_FAILED", beforeCall);
            return;
        }

        try {
            var result = runtime.cancel(preparation.command());
            tasks.applyCancelResult(executionId, result);
            delivered(record);
        } catch (CallException runtimeFailure) {
            handleRuntimeFailure(record, executionId, true, runtimeFailure);
        } catch (RuntimeException unknown) {
            markUnknown(record, executionId, true,
                    "A2A_CANCEL_OUTCOME_UNKNOWN", unknown.getMessage());
        }
    }

    private void handleRuntimeFailure(
            OutboxRecord record, String executionId, boolean cancellation, CallException failure) {
        if (failure.failureKind() == FailureKind.DEFINITELY_NOT_SENT) {
            retryBeforeCall(record, executionId, cancellation, failure.code(), failure);
            return;
        }
        if (failure.failureKind() == FailureKind.PERMANENT && !cancellation) {
            try {
                tasks.failDispatch(executionId, failure.code(), failure.getMessage());
            } catch (RuntimeException projectionFailure) {
                log.warn("[A2AOutbox] failed to project permanent dispatch failure for {}: {}",
                        executionId, safe(projectionFailure.getMessage()));
            }
            dead(record, record.attemptCount() + 1, failure.code(), failure.getMessage());
            return;
        }
        markUnknown(record, executionId, cancellation, failure.code(), failure.getMessage());
    }

    private void retryBeforeCall(
            OutboxRecord record,
            String executionId,
            boolean cancellation,
            String code,
            RuntimeException failure) {
        int attempts = record.attemptCount() + 1;
        if (attempts < Math.max(1, properties.getOutboxMaxAttempts())) {
            long seconds = Math.min(60L, 1L << Math.min(attempts, 6));
            try {
                outbox.markRetry(record.id(), workerId, attempts, now().plusSeconds(seconds),
                        code, safe(failure.getMessage()));
            } catch (RuntimeException leaseLost) {
                log.warn("[A2AOutbox] retry lease lost for {}: {}",
                        record.id(), safe(leaseLost.getMessage()));
            }
            return;
        }
        if (cancellation) {
            markUnknown(record, executionId, true,
                    "A2A_CANCEL_NOT_DELIVERED", "Cancellation could not reach Runtime");
            return;
        }
        try {
            tasks.failDispatch(executionId, "A2A_RUNTIME_UNAVAILABLE",
                    "Runtime could not be reached before any dispatch attempt was sent");
        } catch (RuntimeException projectionFailure) {
            log.warn("[A2AOutbox] failed to project exhausted dispatch for {}: {}",
                    executionId, safe(projectionFailure.getMessage()));
        }
        dead(record, attempts, code, failure.getMessage());
    }

    private void markUnknown(
            OutboxRecord record,
            String executionId,
            boolean cancellation,
            String code,
            String summary) {
        try {
            tasks.markOutcomeUnknown(executionId, cancellation, code, safe(summary));
        } catch (RuntimeException projectionFailure) {
            log.warn("[A2AOutbox] failed to mark outcome unknown for {}: {}",
                    executionId, safe(projectionFailure.getMessage()));
        }
        dead(record, record.attemptCount() + 1, code, summary);
    }

    private void recoverExpiredClaims() {
        List<OutboxRecord> expired;
        try {
            expired = outbox.markExpiredClaimsUnknown(now(), 100);
        } catch (RuntimeException recoveryFailure) {
            log.warn("[A2AOutbox] expired lease recovery failed: {}", safe(recoveryFailure.getMessage()));
            return;
        }
        for (OutboxRecord record : expired) {
            try {
                tasks.markOutcomeUnknown(
                        executionId(record), isCancel(record.eventType()),
                        "A2A_DISPATCH_OUTCOME_UNKNOWN",
                        "Worker lease expired after Runtime delivery may have started");
            } catch (RuntimeException projectionFailure) {
                log.warn("[A2AOutbox] expired lease projection failed for {}: {}",
                        record.id(), safe(projectionFailure.getMessage()));
            }
        }
    }

    private void delivered(OutboxRecord record) {
        try {
            outbox.markDelivered(record.id(), workerId, now());
        } catch (RuntimeException leaseLost) {
            log.warn("[A2AOutbox] delivery acknowledgement lost for {}: {}",
                    record.id(), safe(leaseLost.getMessage()));
        }
    }

    private void dead(OutboxRecord record, int attempts, String code, String summary) {
        try {
            outbox.markDead(record.id(), workerId, attempts, code, safe(summary));
        } catch (RuntimeException leaseLost) {
            log.warn("[A2AOutbox] dead-letter acknowledgement lost for {}: {}",
                    record.id(), safe(leaseLost.getMessage()));
        }
    }

    private String executionId(OutboxRecord record) {
        try {
            Map<String, Object> value = objectMapper.readValue(record.resourceRefJson(), MAP_TYPE);
            String executionId = value.get("executionId") == null
                    ? "" : String.valueOf(value.get("executionId")).trim();
            if (executionId.isEmpty() || executionId.length() > 128) {
                throw new IllegalArgumentException("executionId is invalid");
            }
            return executionId;
        } catch (Exception exception) {
            throw new IllegalArgumentException("outbox resourceRefJson is invalid", exception);
        }
    }

    private boolean isDispatch(String eventType) {
        return "EXECUTION_DISPATCH_REQUESTED".equals(eventType)
                || "EXECUTION_RESUME_REQUESTED".equals(eventType);
    }

    private boolean isCancel(String eventType) {
        return "EXECUTION_CANCEL_REQUESTED".equals(eventType)
                || "EXECUTION_TIMEOUT_CANCEL_REQUESTED".equals(eventType);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "A2A outbox processing failed";
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private record ActiveLease(long id, String workerId) {
    }
}
