package com.enterprise.ai.runtime.automation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.enterprise.ai.runtime.automation.RuntimeAutomationViews.AutomationDetailView;
import static com.enterprise.ai.runtime.automation.RuntimeAutomationViews.AutomationSummaryView;
import static com.enterprise.ai.runtime.automation.RuntimeAutomationViews.AutomationVersionView;
import static com.enterprise.ai.runtime.automation.RuntimeAutomationViews.AttemptView;
import static com.enterprise.ai.runtime.automation.RuntimeAutomationViews.ExecutionPolicyCommand;
import static com.enterprise.ai.runtime.automation.RuntimeAutomationViews.OccurrenceView;
import static com.enterprise.ai.runtime.automation.RuntimeAutomationViews.ReadinessView;
import static com.enterprise.ai.runtime.automation.RuntimeAutomationViews.UpsertCommand;

@Service
@RequiredArgsConstructor
public class RuntimeAutomationService {

    private final RuntimeAutomationMapper automationMapper;
    private final RuntimeAutomationVersionMapper versionMapper;
    private final RuntimeAutomationOccurrenceMapper occurrenceMapper;
    private final RuntimeAutomationAttemptMapper attemptMapper;
    private final RuntimeAutomationEventMapper eventMapper;
    private final RuntimeAutomationEngineCommandMapper commandMapper;
    private final RuntimeAutomationScheduleCalculator scheduleCalculator;
    private final RuntimeAutomationTargetValidator targetValidator;
    private final RuntimeAutomationJsonSupport json;
    private final ObjectProvider<RuntimeAutomationEnginePort> engineProvider;

    @Value("${reachai.runtime.automation.enabled:false}")
    private boolean enabled;

    public List<AutomationSummaryView> list(String tenantId,
                                            String projectCode,
                                            String status,
                                            String keyword,
                                            int limit,
                                            Actor actor) {
        Actor verifiedActor = requireActor(actor);
        if (StringUtils.hasText(tenantId) && !verifiedActor.tenantId().equals(tenantId.trim())) {
            throw new RuntimeAutomationException(
                    HttpStatus.FORBIDDEN, "AUTOMATION_TENANT_FORBIDDEN",
                    "Automation tenant scope does not match the attested platform identity");
        }
        var query = Wrappers.<RuntimeAutomationEntity>lambdaQuery()
                .eq(RuntimeAutomationEntity::getTenantId, verifiedActor.tenantId())
                .eq(StringUtils.hasText(projectCode), RuntimeAutomationEntity::getProjectCode, trim(projectCode))
                .eq(StringUtils.hasText(status), RuntimeAutomationEntity::getStatus,
                        RuntimeAutomationTypes.upper(status))
                .and(StringUtils.hasText(keyword), nested -> nested
                        .like(RuntimeAutomationEntity::getName, trim(keyword))
                        .or().like(RuntimeAutomationEntity::getAutomationKey, trim(keyword)))
                .orderByDesc(RuntimeAutomationEntity::getUpdatedAt)
                .last("LIMIT " + Math.max(1, Math.min(500, limit)));
        List<RuntimeAutomationEntity> automations = automationMapper.selectList(query);
        Map<Long, RuntimeAutomationVersionEntity> versions = versionsById(automations.stream()
                .map(RuntimeAutomationEntity::getCurrentVersionId).filter(Objects::nonNull).toList());
        return automations.stream().map(item -> summary(item, versions.get(item.getCurrentVersionId()))).toList();
    }

    public AutomationDetailView get(String automationKey, Actor actor) {
        Actor verifiedActor = requireActor(actor);
        RuntimeAutomationEntity automation = required(automationKey, verifiedActor);
        List<RuntimeAutomationVersionEntity> versions = versionMapper.selectList(
                Wrappers.<RuntimeAutomationVersionEntity>lambdaQuery()
                        .eq(RuntimeAutomationVersionEntity::getAutomationId, automation.getId())
                        .orderByDesc(RuntimeAutomationVersionEntity::getVersionNo));
        RuntimeAutomationVersionEntity current = versions.stream()
                .filter(item -> Objects.equals(item.getId(), automation.getCurrentVersionId()))
                .findFirst().orElse(null);
        return new AutomationDetailView(
                summary(automation, current),
                current == null ? null : versionView(current),
                versions.stream().map(this::versionView).toList(),
                occurrences(automationKey, null, 20, verifiedActor));
    }

    @Transactional
    public AutomationDetailView create(UpsertCommand command, Actor actor) {
        Actor verifiedActor = requireActor(actor);
        NormalizedDefinition definition = normalize(command, null, verifiedActor);
        LocalDateTime now = now();
        RuntimeAutomationEntity automation = new RuntimeAutomationEntity();
        automation.setAutomationKey("aut_" + UUID.randomUUID().toString().replace("-", ""));
        automation.setTenantId(definition.tenantId());
        automation.setProjectId(definition.target().projectId());
        automation.setProjectCode(definition.target().projectCode());
        automation.setName(definition.name());
        automation.setDescription(definition.description());
        automation.setStatus(definition.activate() ? "ACTIVE" : "DRAFT");
        automation.setRevision(1L);
        automation.setCreatedBy(verifiedActor.userId());
        automation.setUpdatedBy(verifiedActor.userId());
        automation.setCreatedAt(now);
        automation.setUpdatedAt(now);
        automationMapper.insert(automation);

        RuntimeAutomationVersionEntity version = newVersion(automation, 1, definition, verifiedActor, now);
        versionMapper.insert(version);
        automation.setCurrentVersionId(version.getId());
        automation.setNextFireAt(definition.activate()
                ? RuntimeAutomationScheduleCalculator.utc(scheduleCalculator.next(version, Instant.now())) : null);
        automationMapper.updateById(automation);
        command(automation, version, definition.activate() ? "UPSERT" : "CANCEL");
        event(automation.getId(), null, "AUTOMATION_CREATED", verifiedActor,
                Map.of("status", automation.getStatus(), "versionId", version.getId()));
        return get(automation.getAutomationKey(), verifiedActor);
    }

    @Transactional
    public AutomationDetailView update(String automationKey, UpsertCommand command, Actor actor) {
        Actor verifiedActor = requireActor(actor);
        RuntimeAutomationEntity current = required(automationKey, verifiedActor);
        requireExpectedRevision(command == null ? null : command.expectedRevision(), current);
        if (!RuntimeAutomationTypes.MUTABLE_STATES.contains(current.getStatus())) {
            throw RuntimeAutomationException.conflict(
                    "AUTOMATION_NOT_MUTABLE", "Automation cannot be edited in state " + current.getStatus());
        }
        NormalizedDefinition definition = normalize(command, current, verifiedActor);
        Integer latestVersion = Optional.ofNullable(versionMapper.selectOne(
                        Wrappers.<RuntimeAutomationVersionEntity>lambdaQuery()
                                .eq(RuntimeAutomationVersionEntity::getAutomationId, current.getId())
                                .orderByDesc(RuntimeAutomationVersionEntity::getVersionNo).last("LIMIT 1")))
                .map(RuntimeAutomationVersionEntity::getVersionNo).orElse(0);
        RuntimeAutomationVersionEntity version = newVersion(
                current, latestVersion + 1, definition, verifiedActor, now());
        versionMapper.insert(version);
        String nextStatus = definition.activate() ? "ACTIVE"
                : ("DRAFT".equals(current.getStatus()) ? "DRAFT" : "PAUSED");
        LocalDateTime nextFireAt = "ACTIVE".equals(nextStatus)
                ? RuntimeAutomationScheduleCalculator.utc(scheduleCalculator.next(version, Instant.now())) : null;
        if (automationMapper.updateVersion(
                current.getId(), current.getRevision(), definition.name(), definition.description(),
                definition.target().projectId(), definition.target().projectCode(), version.getId(),
                nextStatus, nextFireAt, verifiedActor.userId()) != 1) {
            throw revisionConflict();
        }
        RuntimeAutomationEntity updated = automationMapper.selectById(current.getId());
        command(updated, version, "ACTIVE".equals(nextStatus) ? "UPSERT" : "CANCEL");
        if (!"ACTIVE".equals(nextStatus)) occurrenceMapper.cancelPendingForAutomation(current.getId());
        event(current.getId(), null, "AUTOMATION_VERSION_CREATED", verifiedActor,
                Map.of("versionId", version.getId(), "status", nextStatus));
        return get(automationKey, verifiedActor);
    }

    @Transactional
    public AutomationDetailView pause(String automationKey, Long expectedRevision, Actor actor) {
        return transition(automationKey, expectedRevision, "PAUSED", actor);
    }

    @Transactional
    public AutomationDetailView resume(String automationKey, Long expectedRevision, Actor actor) {
        Actor verifiedActor = requireActor(actor);
        RuntimeAutomationEntity automation = required(automationKey, verifiedActor);
        RuntimeAutomationVersionEntity version = requiredCurrentVersion(automation);
        validateStoredTarget(automation, version);
        return transition(automationKey, expectedRevision, "ACTIVE", verifiedActor);
    }

    @Transactional
    public AutomationDetailView archive(String automationKey, Long expectedRevision, Actor actor) {
        return transition(automationKey, expectedRevision, "ARCHIVED", actor);
    }

    @Transactional
    public OccurrenceView runNow(String automationKey, Actor actor) {
        Actor verifiedActor = requireActor(actor);
        RuntimeAutomationEntity automation = required(automationKey, verifiedActor);
        if ("ARCHIVED".equals(automation.getStatus())) {
            throw RuntimeAutomationException.conflict(
                    "AUTOMATION_ARCHIVED", "Archived Automation cannot be run");
        }
        RuntimeAutomationVersionEntity version = requiredCurrentVersion(automation);
        validateStoredTarget(automation, version);
        RuntimeAutomationOccurrenceEntity occurrence = insertManualOccurrence(
                automation, version, "MANUAL", "manual:" + UUID.randomUUID());
        event(automation.getId(), occurrence.getId(), "AUTOMATION_RUN_REQUESTED", verifiedActor, Map.of());
        return occurrenceView(automation, occurrence, List.of());
    }

    @Transactional
    public OccurrenceView retry(String automationKey, Long occurrenceId, Actor actor) {
        Actor verifiedActor = requireActor(actor);
        RuntimeAutomationEntity automation = required(automationKey, verifiedActor);
        RuntimeAutomationOccurrenceEntity original = occurrenceMapper.selectById(occurrenceId);
        if (original == null || !automation.getId().equals(original.getAutomationId())) {
            throw new RuntimeAutomationException(
                    HttpStatus.NOT_FOUND, "AUTOMATION_OCCURRENCE_NOT_FOUND", "Automation occurrence not found");
        }
        if (!RuntimeAutomationTypes.TERMINAL_OCCURRENCE_STATES.contains(original.getStatus())) {
            throw RuntimeAutomationException.conflict(
                    "AUTOMATION_OCCURRENCE_NOT_TERMINAL", "Only a terminal occurrence can be retried");
        }
        RuntimeAutomationVersionEntity version = versionMapper.selectById(original.getAutomationVersionId());
        if (version == null) {
            throw RuntimeAutomationException.conflict(
                    "AUTOMATION_VERSION_MISSING", "Pinned Automation version is missing");
        }
        RuntimeAutomationOccurrenceEntity retry = insertManualOccurrence(
                automation, version, "RETRY", "retry:" + original.getId() + ":" + UUID.randomUUID());
        event(automation.getId(), retry.getId(), "AUTOMATION_RETRY_REQUESTED", verifiedActor,
                Map.of("originalOccurrenceId", original.getId()));
        return occurrenceView(automation, retry, List.of());
    }

    @Transactional
    public OccurrenceView cancelOccurrence(String automationKey,
                                           Long occurrenceId,
                                           String reason,
                                           Actor actor) {
        Actor verifiedActor = requireActor(actor);
        RuntimeAutomationEntity automation = required(automationKey, verifiedActor);
        RuntimeAutomationOccurrenceEntity occurrence = occurrenceMapper.selectById(occurrenceId);
        if (occurrence == null || !automation.getId().equals(occurrence.getAutomationId())) {
            throw new RuntimeAutomationException(
                    HttpStatus.NOT_FOUND, "AUTOMATION_OCCURRENCE_NOT_FOUND", "Automation occurrence not found");
        }
        if (occurrenceMapper.cancelPending(occurrenceId,
                json.limit(defaultText(reason, "Cancelled by operator"), 1000)) != 1) {
            throw RuntimeAutomationException.conflict(
                    "AUTOMATION_OCCURRENCE_NOT_CANCELLABLE", "Only pending occurrences can be cancelled");
        }
        event(automation.getId(), occurrenceId, "AUTOMATION_OCCURRENCE_CANCELLED", verifiedActor, Map.of());
        return occurrenceView(automation, occurrenceMapper.selectById(occurrenceId), attempts(occurrenceId));
    }

    public List<OccurrenceView> occurrences(String automationKey,
                                            String status,
                                            int limit,
                                            Actor actor) {
        Actor verifiedActor = requireActor(actor);
        RuntimeAutomationEntity automation = required(automationKey, verifiedActor);
        List<RuntimeAutomationOccurrenceEntity> values = occurrenceMapper.selectList(
                Wrappers.<RuntimeAutomationOccurrenceEntity>lambdaQuery()
                        .eq(RuntimeAutomationOccurrenceEntity::getAutomationId, automation.getId())
                        .eq(StringUtils.hasText(status), RuntimeAutomationOccurrenceEntity::getStatus,
                                RuntimeAutomationTypes.upper(status))
                        .orderByDesc(RuntimeAutomationOccurrenceEntity::getScheduledAt)
                        .orderByDesc(RuntimeAutomationOccurrenceEntity::getId)
                        .last("LIMIT " + Math.max(1, Math.min(500, limit))));
        Map<Long, List<RuntimeAutomationAttemptEntity>> attempts = attemptsByOccurrence(
                values.stream().map(RuntimeAutomationOccurrenceEntity::getId).toList());
        return values.stream().map(item -> occurrenceView(
                automation, item, attempts.getOrDefault(item.getId(), List.of()))).toList();
    }

    public ReadinessView readiness() {
        RuntimeAutomationEnginePort engine = engineProvider.getIfAvailable();
        return new ReadinessView(
                enabled && engine != null,
                engine == null ? "db-scheduler" : engine.engineName(),
                "16.11.0",
                "db-scheduler materializes idempotent occurrences only",
                "Runtime leased worker executes exact published targets",
                "UTC in runtime_scheduler_task and Automation timestamps",
                enabled ? "Automation engine is enabled" : "Set REACHAI_AUTOMATION_ENABLED=true after applying SQL");
    }

    private AutomationDetailView transition(String automationKey,
                                            Long expectedRevision,
                                            String nextStatus,
                                            Actor actor) {
        Actor verifiedActor = requireActor(actor);
        RuntimeAutomationEntity current = required(automationKey, verifiedActor);
        if ("ARCHIVED".equals(current.getStatus())) {
            throw RuntimeAutomationException.conflict(
                    "AUTOMATION_ARCHIVED", "Archived Automation is immutable");
        }
        requireExpectedRevision(expectedRevision, current);
        RuntimeAutomationVersionEntity version = requiredCurrentVersion(current);
        LocalDateTime nextFire = "ACTIVE".equals(nextStatus)
                ? RuntimeAutomationScheduleCalculator.utc(scheduleCalculator.next(version, Instant.now())) : null;
        if ("ACTIVE".equals(nextStatus) && nextFire == null) {
            throw RuntimeAutomationException.conflict(
                    "AUTOMATION_SCHEDULE_EXHAUSTED",
                    "The pinned schedule has no future fire time; create a new version instead");
        }
        if (automationMapper.transition(current.getId(), current.getRevision(), nextStatus,
                nextFire, verifiedActor.userId()) != 1) {
            throw revisionConflict();
        }
        RuntimeAutomationEntity updated = automationMapper.selectById(current.getId());
        command(updated, version, "ACTIVE".equals(nextStatus) ? "UPSERT" : "CANCEL");
        if (!"ACTIVE".equals(nextStatus)) occurrenceMapper.cancelPendingForAutomation(current.getId());
        event(current.getId(), null, "AUTOMATION_" + nextStatus, verifiedActor, Map.of());
        return get(automationKey, verifiedActor);
    }

    private NormalizedDefinition normalize(UpsertCommand command,
                                           RuntimeAutomationEntity existing,
                                           Actor actor) {
        if (command == null) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_COMMAND_REQUIRED", "Automation definition is required");
        }
        String name = requireText(command.name(), "name", 128);
        String tenant = defaultText(command.tenantId(), existing == null ? actor.tenantId() : existing.getTenantId());
        if (!tenant.equals(actor.tenantId())) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_TENANT_MISMATCH", "Automation tenant must match the attested actor tenant");
        }
        RuntimeAutomationTargetValidator.TargetSnapshot target = targetValidator.validate(
                command.target(),
                command.projectId() == null && existing != null ? existing.getProjectId() : command.projectId(),
                !StringUtils.hasText(command.projectCode()) && existing != null
                        ? existing.getProjectCode() : command.projectCode());
        RuntimeAutomationScheduleCalculator.NormalizedSchedule schedule = scheduleCalculator.normalize(command.schedule());
        NormalizedExecutionPolicy policy = normalizePolicy(command.executionPolicy());
        Map<String, Object> input = json.normalizeInput(command.input());
        boolean activate = Boolean.TRUE.equals(command.activate());
        return new NormalizedDefinition(
                name,
                json.limit(trim(command.description()), 1000),
                tenant,
                target,
                schedule,
                policy,
                input,
                activate);
    }

    private NormalizedExecutionPolicy normalizePolicy(ExecutionPolicyCommand command) {
        ExecutionPolicyCommand value = command == null
                ? new ExecutionPolicyCommand(null, null, null, null, null, null) : command;
        String concurrency = defaultText(RuntimeAutomationTypes.upper(value.concurrencyPolicy()), "QUEUE");
        if (!RuntimeAutomationTypes.CONCURRENCY_POLICIES.contains(concurrency)) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_CONCURRENCY_INVALID", "unsupported concurrency policy: " + concurrency);
        }
        int maxConcurrent = bounded(value.maxConcurrentRuns(), 1, 1, 32, "maxConcurrentRuns");
        if (!"ALLOW".equals(concurrency)) maxConcurrent = 1;
        return new NormalizedExecutionPolicy(
                concurrency,
                maxConcurrent,
                bounded(value.timeoutSeconds(), 900, 10, 86_400, "timeoutSeconds"),
                bounded(value.maxAttempts(), 3, 1, 20, "maxAttempts"),
                bounded(value.initialBackoffSeconds(), 10, 1, 3600, "initialBackoffSeconds"),
                bounded(value.maxBackoffSeconds(), 300, 1, 86_400, "maxBackoffSeconds"));
    }

    private RuntimeAutomationVersionEntity newVersion(RuntimeAutomationEntity automation,
                                                        int versionNo,
                                                        NormalizedDefinition definition,
                                                        Actor actor,
                                                        LocalDateTime now) {
        RuntimeAutomationVersionEntity version = new RuntimeAutomationVersionEntity();
        version.setAutomationId(automation.getId());
        version.setVersionNo(versionNo);
        version.setTargetType(definition.target().type());
        version.setTargetId(definition.target().id());
        version.setTargetVersionId(definition.target().versionId());
        Map<String, Object> targetSnapshot = new LinkedHashMap<>(definition.target().metadata());
        targetSnapshot.put("fingerprintSha256", definition.target().fingerprint());
        version.setTargetSnapshotJson(json.write(targetSnapshot));
        version.setTriggerType(definition.schedule().type());
        version.setCronExpression(definition.schedule().cronExpression());
        version.setFireAt(RuntimeAutomationScheduleCalculator.utc(definition.schedule().fireAt()));
        version.setTimeZone(definition.schedule().timeZone().getId());
        version.setMisfirePolicy(definition.schedule().misfirePolicy());
        version.setMisfireGraceSeconds(definition.schedule().misfireGraceSeconds());
        version.setMaxCatchUp(definition.schedule().maxCatchUp());
        version.setConcurrencyPolicy(definition.policy().concurrencyPolicy());
        version.setMaxConcurrentRuns(definition.policy().maxConcurrentRuns());
        version.setTimeoutSeconds(definition.policy().timeoutSeconds());
        version.setMaxAttempts(definition.policy().maxAttempts());
        version.setInitialBackoffSeconds(definition.policy().initialBackoffSeconds());
        version.setMaxBackoffSeconds(definition.policy().maxBackoffSeconds());
        version.setInputJson(json.write(definition.input()));
        version.setPrincipalType("AUTOMATION_SERVICE_ACCOUNT");
        version.setPrincipalId("automation:" + automation.getAutomationKey());
        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("principalType", version.getPrincipalType());
        principal.put("principalId", version.getPrincipalId());
        principal.put("tenantId", definition.tenantId());
        principal.put("projectId", definition.target().projectId());
        principal.put("projectCode", definition.target().projectCode());
        principal.put("authorizedBy", actor.userId());
        principal.put("authorizationSource", "CONTROL_PLATFORM_SESSION");
        principal.put("userTrusted", false);
        version.setPrincipalSnapshotJson(json.write(principal));
        version.setInteractionPolicy("FAIL_CLOSED");
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("target", targetSnapshot);
        fingerprint.put("triggerType", version.getTriggerType());
        fingerprint.put("cronExpression", version.getCronExpression());
        fingerprint.put("fireAt", definition.schedule().fireAt());
        fingerprint.put("timeZone", version.getTimeZone());
        fingerprint.put("misfirePolicy", version.getMisfirePolicy());
        fingerprint.put("concurrencyPolicy", version.getConcurrencyPolicy());
        fingerprint.put("input", definition.input());
        fingerprint.put("principal", principal);
        version.setFingerprintSha256(json.fingerprint(fingerprint));
        version.setCreatedBy(actor.userId());
        version.setCreatedAt(now);
        return version;
    }

    private RuntimeAutomationOccurrenceEntity insertManualOccurrence(
            RuntimeAutomationEntity automation,
            RuntimeAutomationVersionEntity version,
            String source,
            String key) {
        RuntimeAutomationOccurrenceEntity occurrence = new RuntimeAutomationOccurrenceEntity();
        LocalDateTime now = now();
        occurrence.setOccurrenceKey(key);
        occurrence.setAutomationId(automation.getId());
        occurrence.setAutomationVersionId(version.getId());
        occurrence.setSourceType(source);
        occurrence.setScheduledAt(now);
        occurrence.setAvailableAt(now);
        occurrence.setStatus("PENDING");
        occurrence.setPriority(100);
        occurrence.setAttemptCount(0);
        occurrence.setMaxAttempts(version.getMaxAttempts());
        occurrence.setInputSnapshotJson(version.getInputJson());
        occurrence.setPrincipalSnapshotJson(version.getPrincipalSnapshotJson());
        occurrence.setCreatedAt(now);
        occurrence.setUpdatedAt(now);
        occurrenceMapper.insert(occurrence);
        return occurrence;
    }

    private void validateStoredTarget(RuntimeAutomationEntity automation,
                                      RuntimeAutomationVersionEntity version) {
        targetValidator.validate(new RuntimeAutomationViews.TargetCommand(
                        version.getTargetType(), version.getTargetId(), version.getTargetVersionId()),
                automation.getProjectId(), automation.getProjectCode());
    }

    private RuntimeAutomationEntity required(String automationKey, Actor actor) {
        if (!StringUtils.hasText(automationKey)) throw RuntimeAutomationException.notFound("Automation not found");
        RuntimeAutomationEntity value = automationMapper.selectOne(
                Wrappers.<RuntimeAutomationEntity>lambdaQuery()
                        .eq(RuntimeAutomationEntity::getAutomationKey, automationKey.trim())
                        .eq(RuntimeAutomationEntity::getTenantId, actor.tenantId())
                        .last("LIMIT 1"));
        if (value == null) throw RuntimeAutomationException.notFound("Automation not found: " + automationKey);
        return value;
    }

    private RuntimeAutomationVersionEntity requiredCurrentVersion(RuntimeAutomationEntity automation) {
        RuntimeAutomationVersionEntity version = automation.getCurrentVersionId() == null
                ? null : versionMapper.selectById(automation.getCurrentVersionId());
        if (version == null || !automation.getId().equals(version.getAutomationId())) {
            throw RuntimeAutomationException.conflict(
                    "AUTOMATION_VERSION_MISSING", "Automation has no valid current version");
        }
        return version;
    }

    private void command(RuntimeAutomationEntity automation,
                         RuntimeAutomationVersionEntity version,
                         String type) {
        RuntimeAutomationEngineCommandEntity command = new RuntimeAutomationEngineCommandEntity();
        command.setAutomationId(automation.getId());
        command.setAutomationVersionId(version == null ? null : version.getId());
        command.setCommandType(type);
        command.setStatus("PENDING");
        command.setAttemptCount(0);
        command.setAvailableAt(now());
        command.setCreatedAt(now());
        command.setUpdatedAt(now());
        commandMapper.insert(command);
    }

    private void event(Long automationId,
                       Long occurrenceId,
                       String type,
                       Actor actor,
                       Map<String, Object> detail) {
        RuntimeAutomationEventEntity event = new RuntimeAutomationEventEntity();
        event.setAutomationId(automationId);
        event.setOccurrenceId(occurrenceId);
        event.setEventType(type);
        event.setActorType("PLATFORM_USER");
        event.setActorId(actor.userId());
        event.setDetailJson(json.write(detail));
        event.setCreatedAt(now());
        eventMapper.insert(event);
    }

    private AutomationSummaryView summary(RuntimeAutomationEntity automation,
                                           RuntimeAutomationVersionEntity version) {
        return new AutomationSummaryView(
                automation.getAutomationKey(), automation.getName(), automation.getDescription(),
                automation.getTenantId(), automation.getProjectId(), automation.getProjectCode(),
                automation.getStatus(), value(automation.getRevision(), 0L),
                version == null ? null : version.getTargetType(),
                version == null ? null : version.getTargetId(),
                version == null ? null : version.getTargetVersionId(),
                version == null ? null : version.getTriggerType(),
                scheduleLabel(version),
                version == null ? null : version.getTimeZone(),
                RuntimeAutomationScheduleCalculator.instantText(automation.getNextFireAt()),
                RuntimeAutomationScheduleCalculator.instantText(automation.getLastFireAt()),
                RuntimeAutomationScheduleCalculator.instantText(automation.getUpdatedAt()));
    }

    private AutomationVersionView versionView(RuntimeAutomationVersionEntity value) {
        return new AutomationVersionView(
                value.getId(), number(value.getVersionNo(), 0), value.getTargetType(), value.getTargetId(),
                value.getTargetVersionId(), json.readMap(value.getTargetSnapshotJson()),
                value.getTriggerType(), value.getCronExpression(),
                RuntimeAutomationScheduleCalculator.instantText(value.getFireAt()), value.getTimeZone(),
                value.getMisfirePolicy(), number(value.getMisfireGraceSeconds(), 60),
                number(value.getMaxCatchUp(), 10), value.getConcurrencyPolicy(),
                number(value.getMaxConcurrentRuns(), 1), number(value.getTimeoutSeconds(), 900),
                number(value.getMaxAttempts(), 3), number(value.getInitialBackoffSeconds(), 10),
                number(value.getMaxBackoffSeconds(), 300), json.readMap(value.getInputJson()),
                value.getPrincipalType(), value.getPrincipalId(), value.getFingerprintSha256(),
                value.getCreatedBy(), RuntimeAutomationScheduleCalculator.instantText(value.getCreatedAt()));
    }

    private OccurrenceView occurrenceView(RuntimeAutomationEntity automation,
                                          RuntimeAutomationOccurrenceEntity occurrence,
                                          List<RuntimeAutomationAttemptEntity> attempts) {
        return new OccurrenceView(
                occurrence.getId(), occurrence.getOccurrenceKey(), automation.getAutomationKey(),
                occurrence.getAutomationVersionId(), occurrence.getSourceType(),
                RuntimeAutomationScheduleCalculator.instantText(occurrence.getScheduledAt()),
                RuntimeAutomationScheduleCalculator.instantText(occurrence.getAvailableAt()),
                occurrence.getStatus(), number(occurrence.getAttemptCount(), 0),
                number(occurrence.getMaxAttempts(), 0), occurrence.getTraceId(), occurrence.getInteractionId(),
                occurrence.getLastErrorCode(), occurrence.getLastErrorMessage(),
                RuntimeAutomationScheduleCalculator.instantText(occurrence.getStartedAt()),
                RuntimeAutomationScheduleCalculator.instantText(occurrence.getCompletedAt()),
                RuntimeAutomationScheduleCalculator.instantText(occurrence.getCreatedAt()),
                attempts.stream().sorted(Comparator.comparing(RuntimeAutomationAttemptEntity::getAttemptNo))
                        .map(this::attemptView).toList());
    }

    private AttemptView attemptView(RuntimeAutomationAttemptEntity value) {
        return new AttemptView(
                value.getId(), number(value.getAttemptNo(), 0), value.getStatus(), value.getWorkerId(),
                value.getTraceId(), value.getResultSummary(), value.getErrorCode(), value.getErrorMessage(),
                RuntimeAutomationScheduleCalculator.instantText(value.getStartedAt()),
                RuntimeAutomationScheduleCalculator.instantText(value.getEndedAt()));
    }

    private Map<Long, RuntimeAutomationVersionEntity> versionsById(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return versionMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(
                RuntimeAutomationVersionEntity::getId, Function.identity()));
    }

    private Map<Long, List<RuntimeAutomationAttemptEntity>> attemptsByOccurrence(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return attemptMapper.selectList(Wrappers.<RuntimeAutomationAttemptEntity>lambdaQuery()
                        .in(RuntimeAutomationAttemptEntity::getOccurrenceId, ids)
                        .orderByAsc(RuntimeAutomationAttemptEntity::getOccurrenceId)
                        .orderByAsc(RuntimeAutomationAttemptEntity::getAttemptNo))
                .stream().collect(Collectors.groupingBy(
                        RuntimeAutomationAttemptEntity::getOccurrenceId, LinkedHashMap::new, Collectors.toList()));
    }

    private List<RuntimeAutomationAttemptEntity> attempts(Long occurrenceId) {
        return attemptMapper.selectList(Wrappers.<RuntimeAutomationAttemptEntity>lambdaQuery()
                .eq(RuntimeAutomationAttemptEntity::getOccurrenceId, occurrenceId)
                .orderByAsc(RuntimeAutomationAttemptEntity::getAttemptNo));
    }

    private void requireExpectedRevision(Long expected, RuntimeAutomationEntity automation) {
        if (expected == null) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_REVISION_REQUIRED", "expectedRevision is required");
        }
        if (!expected.equals(automation.getRevision())) throw revisionConflict();
    }

    private RuntimeAutomationException revisionConflict() {
        return RuntimeAutomationException.conflict(
                "AUTOMATION_REVISION_CONFLICT", "Automation changed; refresh before saving again");
    }

    private Actor requireActor(Actor actor) {
        if (actor == null || !StringUtils.hasText(actor.userId())) {
            throw new RuntimeAutomationException(
                    HttpStatus.UNAUTHORIZED, "AUTOMATION_ACTOR_REQUIRED", "attested platform actor is required");
        }
        return new Actor(defaultText(actor.tenantId(), "default"), actor.userId().trim());
    }

    private int bounded(Integer raw, int fallback, int min, int max, String field) {
        int value = raw == null ? fallback : raw;
        if (value < min || value > max) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_POLICY_INVALID", field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private int number(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private long value(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private String scheduleLabel(RuntimeAutomationVersionEntity version) {
        if (version == null) return null;
        return "CRON".equals(version.getTriggerType())
                ? version.getCronExpression()
                : RuntimeAutomationScheduleCalculator.instantText(version.getFireAt());
    }

    private String requireText(String value, String field, int maximum) {
        if (!StringUtils.hasText(value)) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_FIELD_REQUIRED", field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maximum) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_FIELD_TOO_LONG", field + " must not exceed " + maximum + " characters");
        }
        return normalized;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(java.time.Clock.systemUTC());
    }

    public record Actor(String tenantId, String userId) {
    }

    private record NormalizedDefinition(
            String name,
            String description,
            String tenantId,
            RuntimeAutomationTargetValidator.TargetSnapshot target,
            RuntimeAutomationScheduleCalculator.NormalizedSchedule schedule,
            NormalizedExecutionPolicy policy,
            Map<String, Object> input,
            boolean activate) {
    }

    private record NormalizedExecutionPolicy(
            String concurrencyPolicy,
            int maxConcurrentRuns,
            int timeoutSeconds,
            int maxAttempts,
            int initialBackoffSeconds,
            int maxBackoffSeconds) {
    }
}
