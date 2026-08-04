package com.enterprise.ai.control.aicoding.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ActivationCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ActivationView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ConnectionView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.HandoffPackageView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ActivationStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ActorType;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutorProvider;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.security.AiCodingActivationAttemptService;
import com.enterprise.ai.control.aicoding.security.AiCodingSecretDigester;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiCodingHandoffApplicationService {

    private static final String ACTIVATION_SCHEMA = "reachai.ai-coding.activation.v1";
    private static final String ACTIVATION_RESULT_SCHEMA =
            "reachai.ai-coding.activation-result.v1";

    private final AiCodingTaskMapper taskMapper;
    private final AiCodingTaskHandoffMapper handoffMapper;
    private final AiCodingTaskEventMapper eventMapper;
    private final AiCodingTaskProperties properties;
    private final AiCodingCredentialPolicyService credentialPolicyService;
    private final AiCodingSecretDigester secretDigester;
    private final AiCodingActivationAttemptService attemptService;
    private final AiCodingHandoffPromptFactory promptFactory;
    private final AiCodingPowerShellBootstrapFactory powerShellBootstrapFactory;

    @Transactional
    public HandoffPackageView issue(
            String taskId,
            String publicBaseUrl,
            String issuedBy) {
        AiCodingTaskEntity task = requireTask(taskId);
        String normalizedIssuedBy = textOrNull(
                issuedBy,
                "issuedBy",
                AiCodingTaskValues.ACTOR_NAME_MAX_CHARACTERS);
        ExecutionStatus executionStatus = status(task);
        if (!executionStatus.acceptsClientAccess()) {
            throw new IllegalStateException(
                    "cannot issue a handoff for task " + taskId
                            + " in status " + executionStatus);
        }

        LocalDateTime now = LocalDateTime.now();
        revokeExisting(taskId, now, "REPLACED_BY_NEW_HANDOFF");

        String activationCode = secretDigester.generate("rhc_");
        AiCodingTaskHandoffEntity handoff = new AiCodingTaskHandoffEntity();
        handoff.setHandoffId(newId("handoff_"));
        handoff.setTaskId(taskId);
        handoff.setActivationCodeHash(secretDigester.digest(activationCode));
        handoff.setActivationStatus(ActivationStatus.ISSUED.name());
        handoff.setActivationAttempts(0);
        handoff.setActivationExpiresAt(now.plus(
                credentialPolicyService.handoffActivationTtl(task.getProjectId())));
        handoff.setIssuedBy(normalizedIssuedBy);
        handoff.setCreatedAt(now);
        handoff.setUpdatedAt(now);
        handoffMapper.insert(handoff);

        task.setLastMessage("交接包已签发，等待 " + task.getExecutorProvider() + " 对接");
        task.setUpdatedAt(now);
        requireTaskUpdate(task);
        appendEvent(
                task,
                "HANDOFF_ISSUED",
                task.getLastMessage(),
                ActorType.USER,
                normalizedIssuedBy);

        return promptFactory.build(
                publicBaseUrl,
                task,
                handoff,
                activationCode);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public ActivationView activate(
            String handoffId,
            ActivationCommand command,
            String publicBaseUrl) {
        if (command == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "activation request is required");
        }
        if (!ACTIVATION_SCHEMA.equals(command.schema())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "activation schema must be " + ACTIVATION_SCHEMA);
        }
        AiCodingTaskHandoffEntity handoff = handoffMapper.selectById(
                requireText(handoffId, "handoffId"));
        if (handoff == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "handoff not found");
        }
        AiCodingTaskEntity task = requireTask(handoff.getTaskId());
        ExecutionStatus executionStatus = status(task);
        if (!executionStatus.acceptsClientAccess()) {
            close(
                    handoff,
                    executionStatus.terminal()
                            ? "TASK_TERMINAL"
                            : "TASK_ACCEPTANCE_READY");
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    executionStatus.terminal()
                            ? "task is already terminal"
                            : "task is waiting for ReachAI acceptance");
        }
        if (!ActivationStatus.ISSUED.name().equals(handoff.getActivationStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "handoff is not available for activation");
        }
        LocalDateTime now = LocalDateTime.now();
        if (handoff.getActivationExpiresAt() == null
                || !handoff.getActivationExpiresAt().isAfter(now)) {
            int expired = handoffMapper.update(
                    null,
                    Wrappers.<AiCodingTaskHandoffEntity>lambdaUpdate()
                            .eq(AiCodingTaskHandoffEntity::getHandoffId,
                                    handoff.getHandoffId())
                            .eq(AiCodingTaskHandoffEntity::getActivationStatus,
                                    ActivationStatus.ISSUED.name())
                            .isNull(AiCodingTaskHandoffEntity::getClosedAt)
                            .set(AiCodingTaskHandoffEntity::getActivationStatus,
                                    ActivationStatus.EXPIRED.name())
                            .set(AiCodingTaskHandoffEntity::getClosedAt, now)
                            .set(AiCodingTaskHandoffEntity::getCloseReason,
                                    "ACTIVATION_EXPIRED")
                            .set(AiCodingTaskHandoffEntity::getUpdatedAt, now));
            if (expired != 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "handoff changed while activation was processed");
            }
            throw new ResponseStatusException(
                    HttpStatus.GONE,
                    "handoff activation code has expired");
        }
        int attempts = handoff.getActivationAttempts() == null
                ? 0
                : handoff.getActivationAttempts();
        if (attempts >= properties.getMaxActivationAttempts()) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "handoff activation attempts exceeded");
        }
        String activationCode = requireText(
                command.activationCode(),
                "activationCode",
                256);
        if (!secretDigester.matches(
                activationCode,
                handoff.getActivationCodeHash())) {
            attemptService.recordFailure(
                    handoff,
                    properties.getMaxActivationAttempts());
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "activation code is invalid");
        }

        String clientProvider = requiredProvider(
                command.client() == null ? null : command.client().provider());
        if (!task.getExecutorProvider().equals(clientProvider)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "activation client provider does not match task executorProvider");
        }
        String clientSessionRef = textOrNull(
                command.client() == null
                        ? null
                        : command.client().sessionRef(),
                "client.sessionRef",
                AiCodingTaskValues.CLIENT_SESSION_REF_MAX_CHARACTERS);

        String taskToken = secretDigester.generate("rtt_");
        LocalDateTime tokenExpiresAt = now.plus(
                credentialPolicyService.taskTokenTtl(task.getProjectId()));
        LocalDateTime leaseExpiresAt = now.plus(properties.getConnectionLease());
        int updated = handoffMapper.update(
                null,
                Wrappers.<AiCodingTaskHandoffEntity>lambdaUpdate()
                        .eq(AiCodingTaskHandoffEntity::getHandoffId, handoff.getHandoffId())
                        .eq(AiCodingTaskHandoffEntity::getActivationStatus,
                                ActivationStatus.ISSUED.name())
                        .set(AiCodingTaskHandoffEntity::getActivationStatus,
                                ActivationStatus.ACTIVATED.name())
                        .set(AiCodingTaskHandoffEntity::getTaskTokenHash,
                                secretDigester.digest(taskToken))
                        .set(AiCodingTaskHandoffEntity::getTokenExpiresAt, tokenExpiresAt)
                        .set(AiCodingTaskHandoffEntity::getClientProvider, clientProvider)
                        .set(AiCodingTaskHandoffEntity::getClientSessionRef,
                                clientSessionRef)
                        .set(AiCodingTaskHandoffEntity::getActivatedAt, now)
                        .set(AiCodingTaskHandoffEntity::getLastSeenAt, now)
                        .set(AiCodingTaskHandoffEntity::getLeaseExpiresAt, leaseExpiresAt)
                        .set(AiCodingTaskHandoffEntity::getUpdatedAt, now));
        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "handoff was already consumed");
        }

        task.setLastMessage(clientProvider + " 已与当前任务建立连接");
        task.setUpdatedAt(now);
        requireTaskUpdate(task);
        appendEvent(
                task,
                "CONNECTION_ACTIVATED",
                task.getLastMessage(),
                ActorType.AI_CODING,
                clientProvider);

        String taskRoot = trimTrailingSlash(publicBaseUrl)
                + "/api/ai-coding/tasks/" + task.getTaskId();
        return new ActivationView(
                ACTIVATION_RESULT_SCHEMA,
                task.getTaskId(),
                task.getProtocolVersion(),
                taskToken,
                taskRoot,
                tokenExpiresAt,
                leaseExpiresAt,
                powerShellBootstrapFactory.clientSetup(
                        task.getTaskId(),
                        handoff.getHandoffId(),
                        clientProvider));
    }

    public AiCodingTaskHandoffEntity latest(String taskId) {
        String normalizedTaskId = requireText(taskId, "taskId");
        List<AiCodingTaskHandoffEntity> current = handoffMapper.selectList(
                Wrappers.<AiCodingTaskHandoffEntity>lambdaQuery()
                        .eq(AiCodingTaskHandoffEntity::getTaskId, normalizedTaskId)
                        .isNull(AiCodingTaskHandoffEntity::getClosedAt)
                        .in(AiCodingTaskHandoffEntity::getActivationStatus,
                                ActivationStatus.ISSUED.name(),
                                ActivationStatus.ACTIVATED.name())
                        .orderByDesc(AiCodingTaskHandoffEntity::getCreatedAt)
                        .orderByDesc(AiCodingTaskHandoffEntity::getHandoffId)
                        .last("LIMIT 1"));
        if (!current.isEmpty()) {
            return current.get(0);
        }
        List<AiCodingTaskHandoffEntity> handoffs = handoffMapper.selectList(
                Wrappers.<AiCodingTaskHandoffEntity>lambdaQuery()
                        .eq(AiCodingTaskHandoffEntity::getTaskId, normalizedTaskId)
                        .orderByDesc(AiCodingTaskHandoffEntity::getCreatedAt)
                        .orderByDesc(AiCodingTaskHandoffEntity::getHandoffId)
                        .last("LIMIT 1"));
        return handoffs.isEmpty() ? null : handoffs.get(0);
    }

    public ConnectionView connection(AiCodingTaskEntity task) {
        return AiCodingConnectionStatusCalculator.calculate(
                status(task),
                latest(task.getTaskId()),
                LocalDateTime.now());
    }

    /**
     * Resolves task connections in batches for console lists.
     *
     * <p>At most one open handoff should exist per task. Open records are
     * preferred explicitly so two handoffs created within the same MySQL
     * DATETIME second cannot make a newly issued handoff appear revoked.</p>
     */
    public Map<String, ConnectionView> connections(
            List<AiCodingTaskEntity> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return Map.of();
        }
        Set<String> taskIds = tasks.stream()
                .map(AiCodingTaskEntity::getTaskId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<String, AiCodingTaskHandoffEntity> latest = new LinkedHashMap<>();

        List<AiCodingTaskHandoffEntity> current = handoffMapper.selectList(
                Wrappers.<AiCodingTaskHandoffEntity>lambdaQuery()
                        .in(AiCodingTaskHandoffEntity::getTaskId, taskIds)
                        .isNull(AiCodingTaskHandoffEntity::getClosedAt)
                        .in(AiCodingTaskHandoffEntity::getActivationStatus,
                                ActivationStatus.ISSUED.name(),
                                ActivationStatus.ACTIVATED.name())
                        .orderByDesc(AiCodingTaskHandoffEntity::getCreatedAt)
                        .orderByDesc(AiCodingTaskHandoffEntity::getHandoffId));
        current.forEach(handoff ->
                latest.putIfAbsent(handoff.getTaskId(), handoff));

        Set<String> missing = taskIds.stream()
                .filter(taskId -> !latest.containsKey(taskId))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (!missing.isEmpty()) {
            List<AiCodingTaskHandoffEntity> history = handoffMapper.selectList(
                    Wrappers.<AiCodingTaskHandoffEntity>lambdaQuery()
                            .in(AiCodingTaskHandoffEntity::getTaskId, missing)
                            .orderByDesc(AiCodingTaskHandoffEntity::getCreatedAt)
                            .orderByDesc(AiCodingTaskHandoffEntity::getHandoffId));
            history.forEach(handoff ->
                    latest.putIfAbsent(handoff.getTaskId(), handoff));
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, ConnectionView> result = new LinkedHashMap<>();
        for (AiCodingTaskEntity task : tasks) {
            result.put(
                    task.getTaskId(),
                    AiCodingConnectionStatusCalculator.calculate(
                            status(task),
                            latest.get(task.getTaskId()),
                            now));
        }
        return result;
    }

    @Transactional
    public void closeTaskHandoffs(String taskId, String reason) {
        revokeExisting(taskId, LocalDateTime.now(), reason);
    }

    private void revokeExisting(String taskId, LocalDateTime now, String reason) {
        handoffMapper.update(
                null,
                Wrappers.<AiCodingTaskHandoffEntity>lambdaUpdate()
                        .eq(AiCodingTaskHandoffEntity::getTaskId, taskId)
                        .in(AiCodingTaskHandoffEntity::getActivationStatus,
                                ActivationStatus.ISSUED.name(),
                                ActivationStatus.ACTIVATED.name())
                        .isNull(AiCodingTaskHandoffEntity::getClosedAt)
                        .set(AiCodingTaskHandoffEntity::getActivationStatus,
                                ActivationStatus.REVOKED.name())
                        .set(AiCodingTaskHandoffEntity::getClosedAt, now)
                        .set(AiCodingTaskHandoffEntity::getCloseReason, reason)
                        .set(AiCodingTaskHandoffEntity::getUpdatedAt, now));
    }

    private void close(AiCodingTaskHandoffEntity handoff, String reason) {
        LocalDateTime now = LocalDateTime.now();
        handoff.setActivationStatus(ActivationStatus.REVOKED.name());
        handoff.setClosedAt(now);
        handoff.setCloseReason(reason);
        handoff.setUpdatedAt(now);
        handoffMapper.updateById(handoff);
    }

    private void appendEvent(
            AiCodingTaskEntity task,
            String eventType,
            String message,
            ActorType actorType,
            String actorName) {
        AiCodingTaskEventEntity event = new AiCodingTaskEventEntity();
        event.setTaskId(task.getTaskId());
        event.setEventType(eventType);
        event.setExecutionStatusAfter(task.getExecutionStatus());
        event.setMessage(message);
        event.setActorType(actorType.name());
        event.setActorName(textOrNull(actorName));
        event.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private AiCodingTaskEntity requireTask(String taskId) {
        AiCodingTaskEntity task = taskMapper.selectById(requireText(taskId, "taskId"));
        if (task == null) {
            throw new IllegalArgumentException("AI Coding task not found: " + taskId);
        }
        return task;
    }

    /**
     * Keeps handoff mutations and their console-visible task update atomic.
     *
     * <p>The task entity carries a MyBatis optimistic-lock version. Ignoring a
     * zero-row update could otherwise commit a newly issued or activated
     * handoff after a concurrent cancellation or acceptance transition.</p>
     */
    private void requireTaskUpdate(AiCodingTaskEntity task) {
        if (taskMapper.updateById(task) != 1) {
            throw new IllegalStateException(
                    "AI Coding task changed concurrently; retry the operation");
        }
    }

    private static ExecutionStatus status(AiCodingTaskEntity task) {
        return ExecutionStatus.valueOf(task.getExecutionStatus());
    }

    private static String requiredProvider(String value) {
        return AiCodingTaskValues.requiredEnum(
                ExecutorProvider.class,
                value,
                "client.provider").name();
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String requireText(
            String value,
            String field,
            int maxCharacters) {
        return AiCodingTaskValues.requiredText(
                value,
                field,
                maxCharacters);
    }

    private static String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String textOrNull(
            String value,
            String field,
            int maxCharacters) {
        return AiCodingTaskValues.optionalText(
                value,
                field,
                maxCharacters);
    }

    private static String trimTrailingSlash(String value) {
        String normalized = requireText(value, "publicBaseUrl");
        return normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
