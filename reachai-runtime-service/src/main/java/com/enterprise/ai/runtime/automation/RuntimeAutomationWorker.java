package com.enterprise.ai.runtime.automation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ConditionalOnProperty(name = "reachai.runtime.automation.enabled", havingValue = "true")
final class RuntimeAutomationWorker {

    private final RuntimeAutomationOccurrenceMapper occurrenceMapper;
    private final RuntimeAutomationExecutionSlotMapper slotMapper;
    private final RuntimeAutomationMapper automationMapper;
    private final RuntimeAutomationVersionMapper versionMapper;
    private final RuntimeAutomationTargetExecutor targetExecutor;
    private final RuntimeAutomationExecutionPersistenceService persistence;
    private final RuntimeAutomationJsonSupport json;
    private final ThreadPoolTaskExecutor executor;
    private final Map<Long, ActiveLease> active = new ConcurrentHashMap<>();
    private final String workerId = "automation-" + ProcessHandle.current().pid()
            + "-" + UUID.randomUUID().toString().substring(0, 8);

    @Value("${reachai.runtime.automation.worker-concurrency:4}")
    private int concurrency;

    @Value("${reachai.runtime.automation.worker-lease-seconds:600}")
    private int leaseSeconds;

    RuntimeAutomationWorker(
            RuntimeAutomationOccurrenceMapper occurrenceMapper,
            RuntimeAutomationExecutionSlotMapper slotMapper,
            RuntimeAutomationMapper automationMapper,
            RuntimeAutomationVersionMapper versionMapper,
            RuntimeAutomationTargetExecutor targetExecutor,
            RuntimeAutomationExecutionPersistenceService persistence,
            RuntimeAutomationJsonSupport json,
            @Qualifier("runtimeAutomationExecutor") ThreadPoolTaskExecutor executor) {
        this.occurrenceMapper = occurrenceMapper;
        this.slotMapper = slotMapper;
        this.automationMapper = automationMapper;
        this.versionMapper = versionMapper;
        this.targetExecutor = targetExecutor;
        this.persistence = persistence;
        this.json = json;
        this.executor = executor;
    }

    @Scheduled(
            initialDelayString = "${reachai.runtime.automation.worker-initial-delay-ms:5000}",
            fixedDelayString = "${reachai.runtime.automation.worker-poll-delay-ms:1000}")
    void poll() {
        try {
            persistence.recoverExpiredExecutions();
            int capacity = Math.max(0, Math.max(1, Math.min(32, concurrency)) - active.size());
            for (int i = 0; i < capacity; i++) {
                Claimed claimed = claim();
                if (claimed == null) return;
                ActiveLease lease = acquireSlot(claimed);
                if (lease == null) continue;
                active.put(claimed.occurrence().getId(), lease);
                try {
                    executor.execute(() -> process(claimed, lease));
                } catch (RuntimeException rejected) {
                    active.remove(claimed.occurrence().getId());
                    releaseSlot(lease);
                    occurrenceMapper.defer(claimed.occurrence().getId(), claimed.token(), now().plusSeconds(1));
                    return;
                }
            }
        } catch (Exception failure) {
            log.error("Automation worker poll failed: {}", safe(failure));
        }
    }

    @Scheduled(
            initialDelayString = "${reachai.runtime.automation.worker-heartbeat-ms:30000}",
            fixedDelayString = "${reachai.runtime.automation.worker-heartbeat-ms:30000}")
    void renewActiveLeases() {
        LocalDateTime until = now().plusSeconds(Math.max(60, leaseSeconds));
        active.forEach((occurrenceId, lease) -> {
            int occurrenceRenewed = occurrenceMapper.renew(occurrenceId, lease.token(), until);
            int slotRenewed = slotMapper.renew(
                    lease.automationId(), lease.slotNo(), occurrenceId, lease.token(), until);
            if (occurrenceRenewed != 1 || slotRenewed != 1) {
                log.warn("Automation occurrence {} lost its execution lease", occurrenceId);
            }
        });
    }

    private Claimed claim() {
        for (int collision = 0; collision < 20; collision++) {
            Long id = occurrenceMapper.findLeaseCandidateId();
            if (id == null) return null;
            String token = UUID.randomUUID().toString();
            if (occurrenceMapper.claim(id, workerId, token,
                    now().plusSeconds(Math.max(60, leaseSeconds))) == 1) {
                RuntimeAutomationOccurrenceEntity occurrence = occurrenceMapper.selectById(id);
                if (occurrence != null && token.equals(occurrence.getLeaseToken())) {
                    return new Claimed(occurrence, token);
                }
            }
        }
        return null;
    }

    private ActiveLease acquireSlot(Claimed claimed) {
        RuntimeAutomationVersionEntity version = versionMapper.selectById(
                claimed.occurrence().getAutomationVersionId());
        if (version == null) {
            occurrenceMapper.release(claimed.occurrence().getId(), claimed.token(), "FAILED", now(),
                    "AUTOMATION_VERSION_MISSING", "Pinned Automation version is missing");
            return null;
        }
        int configuredSlots = version.getMaxConcurrentRuns() == null ? 1 : version.getMaxConcurrentRuns();
        int slots = "ALLOW".equals(version.getConcurrencyPolicy())
                ? Math.max(1, Math.min(32, configuredSlots)) : 1;
        for (int slot = 0; slot < slots; slot++) {
            LocalDateTime until = now().plusSeconds(Math.max(60, leaseSeconds));
            slotMapper.tryAcquire(claimed.occurrence().getAutomationId(), slot,
                    claimed.occurrence().getId(), workerId, claimed.token(), until);
            if (slotMapper.owns(claimed.occurrence().getAutomationId(), slot,
                    claimed.occurrence().getId(), claimed.token()) == 1) {
                return new ActiveLease(claimed.occurrence().getAutomationId(), slot,
                        claimed.occurrence().getId(), claimed.token());
            }
        }
        if ("SKIP".equals(version.getConcurrencyPolicy())) {
            occurrenceMapper.release(claimed.occurrence().getId(), claimed.token(), "SKIPPED", now(),
                    "AUTOMATION_CONCURRENCY_SKIPPED", "Another occurrence is already running");
        } else {
            occurrenceMapper.defer(claimed.occurrence().getId(), claimed.token(), now().plusSeconds(1));
        }
        return null;
    }

    private void process(Claimed claimed, ActiveLease lease) {
        RuntimeAutomationAttemptEntity attempt = null;
        RuntimeAutomationVersionEntity version = null;
        try {
            RuntimeAutomationOccurrenceEntity occurrence = claimed.occurrence();
            RuntimeAutomationEntity automation = automationMapper.selectById(occurrence.getAutomationId());
            version = versionMapper.selectById(occurrence.getAutomationVersionId());
            if (automation == null || version == null || !automation.getId().equals(version.getAutomationId())) {
                throw new NonRetryableExecutionException(
                        "AUTOMATION_DEFINITION_MISSING", "Automation or pinned version is missing");
            }
            if ("SCHEDULE".equals(occurrence.getSourceType())
                    && !scheduledOccurrenceMayExecute(automation, version)) {
                occurrenceMapper.release(occurrence.getId(), claimed.token(), "CANCELLED", now(),
                        "AUTOMATION_DEACTIVATED", "Automation was paused, archived, or updated before execution");
                return;
            }
            String traceId = "automation-" + occurrence.getId() + "-a" + occurrence.getAttemptCount()
                    + "-" + UUID.randomUUID().toString().substring(0, 8);
            attempt = persistence.start(occurrence, claimed.token(), workerId, traceId);
            Map<String, Object> input = new LinkedHashMap<>(json.readMap(occurrence.getInputSnapshotJson()));
            input.put("traceId", traceId);
            input.put("supervisorTraceId", traceId);
            input.put("entryType", "AUTOMATION");
            input.put("tenantId", automation.getTenantId());
            input.put("projectCode", automation.getProjectCode());
            input.put("automationKey", automation.getAutomationKey());
            input.put("automationOccurrenceId", occurrence.getId());
            input.put("sessionId", "automation-" + automation.getId() + "-occurrence-" + occurrence.getId());
            input.put("intentHint", "AUTOMATION_EXECUTION");
            RuntimeAutomationTargetExecutor.ExecutionOutcome outcome = targetExecutor.execute(
                    automation, version, occurrence, traceId, input);
            if (outcome.success()) {
                persistence.complete(occurrence, attempt, claimed.token(), outcome);
            } else {
                persistence.fail(occurrence, version, attempt, claimed.token(), outcome);
            }
        } catch (RuntimeAutomationExecutionPersistenceService.StaleAutomationLeaseException stale) {
            log.warn("Discarding stale Automation occurrence result: {}", stale.getMessage());
        } catch (NonRetryableExecutionException failure) {
            if (attempt == null) {
                persistence.failBeforeStart(claimed.occurrence(), version, claimed.token(),
                        failure.code, failure.getMessage(), false);
            } else {
                persistence.fail(claimed.occurrence(), version, attempt, claimed.token(),
                        RuntimeAutomationTargetExecutor.ExecutionOutcome.failure(
                                attempt.getTraceId(), failure.code, failure.getMessage(), false));
            }
        } catch (Exception failure) {
            try {
                if (attempt == null) {
                    persistence.failBeforeStart(claimed.occurrence(), version, claimed.token(),
                            "AUTOMATION_WORKER_FAILED", safe(failure), true);
                } else {
                    persistence.fail(claimed.occurrence(), version, attempt, claimed.token(),
                            RuntimeAutomationTargetExecutor.ExecutionOutcome.failure(
                                    attempt.getTraceId(), "AUTOMATION_WORKER_FAILED", safe(failure), true));
                }
            } catch (Exception persistenceFailure) {
                log.error("Automation occurrence {} failure could not be persisted: {}",
                        claimed.occurrence().getId(), safe(persistenceFailure));
            }
        } finally {
            active.remove(claimed.occurrence().getId());
            releaseSlot(lease);
        }
    }

    static boolean scheduledOccurrenceMayExecute(RuntimeAutomationEntity automation,
                                                 RuntimeAutomationVersionEntity version) {
        if (automation == null || version == null
                || !Objects.equals(version.getId(), automation.getCurrentVersionId())) {
            return false;
        }
        if ("ACTIVE".equals(automation.getStatus())) {
            return true;
        }
        // ONCE completion means no more clock materialization, not cancellation of the occurrence
        // that was durably inserted immediately before the projection moved to COMPLETED.
        return "COMPLETED".equals(automation.getStatus())
                && "ONCE".equals(version.getTriggerType());
    }

    private void releaseSlot(ActiveLease lease) {
        slotMapper.release(lease.automationId(), lease.slotNo(), lease.occurrenceId(), lease.token());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(java.time.Clock.systemUTC());
    }

    private String safe(Throwable error) {
        String value = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private record Claimed(RuntimeAutomationOccurrenceEntity occurrence, String token) {
    }

    private record ActiveLease(Long automationId, int slotNo, Long occurrenceId, String token) {
    }

    private static final class NonRetryableExecutionException extends RuntimeException {
        private final String code;

        private NonRetryableExecutionException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
