package com.enterprise.ai.runtime.automation;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
final class RuntimeAutomationMaterializer {

    private final RuntimeAutomationMapper automationMapper;
    private final RuntimeAutomationVersionMapper versionMapper;
    private final RuntimeAutomationOccurrenceMapper occurrenceMapper;
    private final RuntimeAutomationEventMapper eventMapper;
    private final RuntimeAutomationScheduleCalculator scheduleCalculator;

    void materialize(Long automationId, Long versionId, Instant nominal) {
        RuntimeAutomationEntity automation = automationMapper.selectById(automationId);
        RuntimeAutomationVersionEntity version = versionMapper.selectById(versionId);
        if (automation == null || version == null
                || !"ACTIVE".equals(automation.getStatus())
                || !versionId.equals(automation.getCurrentVersionId())
                || !automationId.equals(version.getAutomationId())) {
            return;
        }
        Instant now = Instant.now();
        List<Instant> due = scheduleCalculator.dueInstants(version, nominal, now);
        if (due.isEmpty()) {
            insertOccurrence(automation, version, nominal, "SKIPPED",
                    "AUTOMATION_MISFIRE_SKIPPED", "Scheduled time exceeded the configured misfire grace period");
            updateClockProjection(automation, version, nominal, now);
            return;
        }
        for (Instant scheduledAt : due) {
            insertOccurrence(automation, version, scheduledAt, "PENDING", null, null);
        }
        updateClockProjection(automation, version, due.get(due.size() - 1), now);
    }

    private void insertOccurrence(RuntimeAutomationEntity automation,
                                  RuntimeAutomationVersionEntity version,
                                  Instant scheduledAt,
                                  String status,
                                  String errorCode,
                                  String errorMessage) {
        if (scheduledAt == null) return;
        RuntimeAutomationOccurrenceEntity occurrence = new RuntimeAutomationOccurrenceEntity();
        occurrence.setOccurrenceKey("scheduled:" + automation.getId() + ":" + version.getId()
                + ":" + scheduledAt.toEpochMilli());
        occurrence.setAutomationId(automation.getId());
        occurrence.setAutomationVersionId(version.getId());
        occurrence.setSourceType("SCHEDULE");
        occurrence.setScheduledAt(RuntimeAutomationScheduleCalculator.utc(scheduledAt));
        occurrence.setAvailableAt(LocalDateTime.now(java.time.Clock.systemUTC()));
        occurrence.setStatus(status);
        occurrence.setPriority(0);
        occurrence.setAttemptCount(0);
        occurrence.setMaxAttempts(version.getMaxAttempts());
        occurrence.setInputSnapshotJson(version.getInputJson());
        occurrence.setPrincipalSnapshotJson(version.getPrincipalSnapshotJson());
        occurrence.setLastErrorCode(errorCode);
        occurrence.setLastErrorMessage(errorMessage);
        occurrence.setCreatedAt(LocalDateTime.now(java.time.Clock.systemUTC()));
        occurrence.setUpdatedAt(occurrence.getCreatedAt());
        if ("SKIPPED".equals(status)) occurrence.setCompletedAt(occurrence.getCreatedAt());
        try {
            occurrenceMapper.insert(occurrence);
            event(automation.getId(), occurrence.getId(), "OCCURRENCE_" + status,
                    "AUTOMATION_ENGINE", errorCode);
        } catch (DuplicateKeyException duplicate) {
            // The occurrence key is the idempotency boundary across scheduler replicas and lease recovery.
        }
    }

    private void updateClockProjection(RuntimeAutomationEntity automation,
                                       RuntimeAutomationVersionEntity version,
                                       Instant lastFire,
                                       Instant now) {
        if ("ONCE".equals(version.getTriggerType())) {
            automationMapper.completeOnce(automation.getId(), version.getId(),
                    RuntimeAutomationScheduleCalculator.utc(lastFire));
            return;
        }
        Instant next = scheduleCalculator.next(version, now);
        automationMapper.updateFireTimes(automation.getId(),
                RuntimeAutomationScheduleCalculator.utc(next),
                RuntimeAutomationScheduleCalculator.utc(lastFire));
    }

    private void event(Long automationId,
                       Long occurrenceId,
                       String type,
                       String actor,
                       String detail) {
        RuntimeAutomationEventEntity event = new RuntimeAutomationEventEntity();
        event.setAutomationId(automationId);
        event.setOccurrenceId(occurrenceId);
        event.setEventType(type);
        event.setActorType("SYSTEM");
        event.setActorId(actor);
        event.setDetailJson(detail == null ? "{}" : "{\"code\":\"" + detail + "\"}");
        event.setCreatedAt(LocalDateTime.now(java.time.Clock.systemUTC()));
        eventMapper.insert(event);
    }
}
