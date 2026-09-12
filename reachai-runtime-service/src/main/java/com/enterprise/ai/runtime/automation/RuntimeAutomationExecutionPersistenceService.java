package com.enterprise.ai.runtime.automation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
class RuntimeAutomationExecutionPersistenceService {

    private final RuntimeAutomationOccurrenceMapper occurrenceMapper;
    private final RuntimeAutomationAttemptMapper attemptMapper;
    private final RuntimeAutomationEventMapper eventMapper;
    private final RuntimeAutomationJsonSupport json;
    private final RuntimeAutomationExecutionSlotMapper slots;
    private final RuntimeRunLifecycleService runs;
    private final RuntimeTraceSpanTerminationService spans;
    private final RuntimeAutomationMapper automations;

    @Transactional
    void recoverExpiredExecutions() {
        for (Long id : occurrenceMapper.findRecoveryCandidateIds()) {
            RuntimeAutomationOccurrenceEntity occurrence = occurrenceMapper.lockExpiredById(id);
            if (occurrence == null) continue;
            closeAbandonedAttempts(occurrence, true);
            RuntimeAutomationEntity automation = automations.selectById(occurrence.getAutomationId());
            String next = automation == null || "ARCHIVED".equals(automation.getStatus())
                    ? "CANCELLED" : exhausted(occurrence) ? "DEAD" : "RETRY";
            if (occurrenceMapper.releaseExpired(id, occurrence.getLeaseToken(), next) != 1) {
                throw new StaleAutomationLeaseException(id);
            }
            slots.releaseLease(occurrence.getAutomationId(), id, occurrence.getLeaseToken());
            event(occurrence, "AUTOMATION_OCCURRENCE_" + next, Map.of("reason", "AUTOMATION_LEASE_EXPIRED"));
        }
    }

    private void closeAbandonedAttempts(RuntimeAutomationOccurrenceEntity occurrence, boolean currentLeaseExpired) {
        List<RuntimeAutomationAttemptEntity> abandoned = attemptMapper.selectList(
                Wrappers.<RuntimeAutomationAttemptEntity>lambdaQuery()
                        .eq(RuntimeAutomationAttemptEntity::getOccurrenceId, occurrence.getId())
                        .eq(RuntimeAutomationAttemptEntity::getStatus, "RUNNING")
                        .last("FOR UPDATE"));
        for (RuntimeAutomationAttemptEntity attempt : abandoned) {
            boolean sameLease = Objects.equals(occurrence.getLeaseToken(), attempt.getLeaseToken());
            if (open(occurrence) && sameLease && !currentLeaseExpired) continue;
            String status = exhausted(occurrence) && sameLease ? "DEAD" : "FAILED";
            String code = "AUTOMATION_LEASE_EXPIRED", message = "Execution lease expired before a terminal result";
            attempt.setStatus(status);
            attempt.setErrorCode(code);
            attempt.setErrorMessage(message);
            attempt.setEndedAt(now());
            attemptMapper.updateById(attempt);
            LocalDateTime traceEndedAt = LocalDateTime.now();
            if (runs.failAbandonedAutomation(attempt.getTraceId(), code, message, traceEndedAt) == 1) {
                spans.failRunning(attempt.getTraceId(), code, message, traceEndedAt);
            }
            slots.releaseLease(occurrence.getAutomationId(), occurrence.getId(), attempt.getLeaseToken());
            event(occurrence, "AUTOMATION_ATTEMPT_ABANDONED", Map.of(
                    "attemptNo", attempt.getAttemptNo(), "reason", code));
        }
    }

    private boolean open(RuntimeAutomationOccurrenceEntity occurrence) {
        return "LEASED".equals(occurrence.getStatus()) || "RUNNING".equals(occurrence.getStatus());
    }

    private boolean exhausted(RuntimeAutomationOccurrenceEntity occurrence) {
        return occurrence.getAttemptCount() >= occurrence.getMaxAttempts();
    }

    private void lockOwnedLease(Long id, String token) {
        RuntimeAutomationOccurrenceEntity current = occurrenceMapper.lockById(id);
        if (current == null || !open(current) || !Objects.equals(current.getLeaseToken(), token)) {
            throw new StaleAutomationLeaseException(id);
        }
    }

    @Transactional
    RuntimeAutomationAttemptEntity start(RuntimeAutomationOccurrenceEntity occurrence,
                                         String token,
                                         String workerId,
                                         String traceId) {
        if (occurrenceMapper.markRunning(occurrence.getId(), token, traceId) != 1) {
            throw new StaleAutomationLeaseException(occurrence.getId());
        }
        closeAbandonedAttempts(occurrenceMapper.selectById(occurrence.getId()), false);
        RuntimeAutomationAttemptEntity attempt = new RuntimeAutomationAttemptEntity();
        attempt.setOccurrenceId(occurrence.getId());
        attempt.setAttemptNo(occurrence.getAttemptCount());
        attempt.setStatus("RUNNING");
        attempt.setWorkerId(workerId);
        attempt.setLeaseToken(token);
        attempt.setTraceId(traceId);
        attempt.setStartedAt(now());
        attempt.setCreatedAt(attempt.getStartedAt());
        attemptMapper.insert(attempt);
        event(occurrence, "AUTOMATION_ATTEMPT_STARTED", Map.of(
                "attemptNo", attempt.getAttemptNo(), "traceId", traceId));
        return attempt;
    }

    @Transactional
    void complete(RuntimeAutomationOccurrenceEntity occurrence,
                  RuntimeAutomationAttemptEntity attempt,
                  String token,
                  RuntimeAutomationTargetExecutor.ExecutionOutcome outcome) {
        lockOwnedLease(occurrence.getId(), token);
        attempt.setStatus("SUCCEEDED");
        attempt.setResultSummary(json.limit(outcome.message(), 4000));
        attempt.setEndedAt(now());
        attemptMapper.updateById(attempt);
        if (occurrenceMapper.complete(occurrence.getId(), token) != 1) {
            throw new StaleAutomationLeaseException(occurrence.getId());
        }
        event(occurrence, "AUTOMATION_OCCURRENCE_SUCCEEDED", Map.of(
                "attemptNo", attempt.getAttemptNo(), "traceId", outcome.traceId()));
    }

    @Transactional
    void fail(RuntimeAutomationOccurrenceEntity occurrence,
              RuntimeAutomationVersionEntity version,
              RuntimeAutomationAttemptEntity attempt,
              String token,
              RuntimeAutomationTargetExecutor.ExecutionOutcome outcome) {
        lockOwnedLease(occurrence.getId(), token);
        String interactionId = interactionId(outcome.metadata());
        if (StringUtils.hasText(interactionId)) {
            occurrenceMapper.recordInteraction(occurrence.getId(), token, interactionId);
        }
        boolean exhausted = occurrence.getAttemptCount() != null && occurrence.getMaxAttempts() != null
                && occurrence.getAttemptCount() >= occurrence.getMaxAttempts();
        boolean retry = outcome.retryable() && !exhausted;
        String nextStatus = retry ? "RETRY" : (exhausted ? "DEAD" : "FAILED");
        LocalDateTime availableAt = retry ? now().plusSeconds(backoff(version, occurrence.getAttemptCount())) : now();
        attempt.setStatus(retry ? "RETRY" : nextStatus);
        attempt.setErrorCode(json.limit(outcome.code(), 96));
        attempt.setErrorMessage(json.limit(outcome.message(), 2000));
        attempt.setResultSummary(json.limit(outcome.message(), 4000));
        attempt.setEndedAt(now());
        attemptMapper.updateById(attempt);
        if (occurrenceMapper.release(
                occurrence.getId(), token, nextStatus, availableAt,
                attempt.getErrorCode(), attempt.getErrorMessage()) != 1) {
            throw new StaleAutomationLeaseException(occurrence.getId());
        }
        event(occurrence, "AUTOMATION_OCCURRENCE_" + nextStatus, Map.of(
                "attemptNo", attempt.getAttemptNo(),
                "traceId", outcome.traceId(),
                "errorCode", defaultText(outcome.code(), "AUTOMATION_EXECUTION_FAILED")));
    }

    @Transactional
    void failBeforeStart(RuntimeAutomationOccurrenceEntity occurrence,
                         RuntimeAutomationVersionEntity version,
                         String token,
                         String code,
                         String message,
                         boolean retryable) {
        RuntimeAutomationAttemptEntity attempt = new RuntimeAutomationAttemptEntity();
        attempt.setOccurrenceId(occurrence.getId());
        attempt.setAttemptNo(occurrence.getAttemptCount());
        attempt.setStatus("FAILED");
        attempt.setWorkerId(occurrence.getLeaseOwner());
        attempt.setLeaseToken(token);
        attempt.setErrorCode(json.limit(code, 96));
        attempt.setErrorMessage(json.limit(message, 2000));
        attempt.setStartedAt(now());
        attempt.setEndedAt(attempt.getStartedAt());
        attempt.setCreatedAt(attempt.getStartedAt());
        attemptMapper.insert(attempt);
        fail(occurrence, version, attempt, token,
                RuntimeAutomationTargetExecutor.ExecutionOutcome.failure(
                        occurrence.getTraceId(), code, message, retryable));
    }

    private long backoff(RuntimeAutomationVersionEntity version, Integer attemptNo) {
        int initial = version == null || version.getInitialBackoffSeconds() == null
                ? 10 : version.getInitialBackoffSeconds();
        int maximum = version == null || version.getMaxBackoffSeconds() == null
                ? 300 : version.getMaxBackoffSeconds();
        int exponent = Math.min(12, Math.max(0, (attemptNo == null ? 1 : attemptNo) - 1));
        return Math.min(maximum, initial * (1L << exponent));
    }

    private String interactionId(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object value = metadata.get("interactionId");
        return value == null ? null : String.valueOf(value).trim();
    }

    private void event(RuntimeAutomationOccurrenceEntity occurrence,
                       String type,
                       Map<String, Object> detail) {
        RuntimeAutomationEventEntity event = new RuntimeAutomationEventEntity();
        event.setAutomationId(occurrence.getAutomationId());
        event.setOccurrenceId(occurrence.getId());
        event.setEventType(type);
        event.setActorType("SYSTEM");
        event.setActorId("AUTOMATION_WORKER");
        event.setDetailJson(json.write(detail));
        event.setCreatedAt(now());
        eventMapper.insert(event);
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(java.time.Clock.systemUTC());
    }

    static final class StaleAutomationLeaseException extends IllegalStateException {
        StaleAutomationLeaseException(Long occurrenceId) {
            super("Automation occurrence lease is stale: " + occurrenceId);
        }
    }
}
