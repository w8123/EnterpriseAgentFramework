package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionMode;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.ApprovalDecisionRequest;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.ApprovalDecisionView;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.ApprovalView;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.ArtifactContent;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.ArtifactView;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.CreateRequest;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.CreatedView;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.ExecutionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/** Control-side application boundary for AI Coding tasks executed by Runtime. */
@Service
@RequiredArgsConstructor
public class AiCodingManagedExecutionService {

    private static final String DEFAULT_TENANT = "default";

    private final AiCodingTaskMapper taskMapper;
    private final AiCodingTaskApplicationService taskService;
    private final AiCodingManagedExecutionProjectionService projectionService;
    private final ControlManagedExecutionRuntimeClient runtimeClient;

    public ManagedExecutionDetailView start(
            String taskId,
            ManagedExecutionStartCommand command,
            String actorUserId) {
        String trustedUserId = actor(actorUserId);
        AiCodingTaskEntity task = requireManagedTask(taskId);
        if (StringUtils.hasText(task.getManagedExecutionId())) {
            return detail(taskId, trustedUserId);
        }
        if (ExecutionStatus.valueOf(task.getExecutionStatus()) != ExecutionStatus.READY) {
            throw new IllegalStateException(
                    "Managed sandbox task must be READY before it is started");
        }
        ManagedExecutionStartCommand effective = command == null
                ? new ManagedExecutionStartCommand(null, null, null, null, null)
                : command;
        CreatedView created = runtimeClient.create(
                new CreateRequest(
                        task.getProjectCode(),
                        "AI_CODING_TASK",
                        task.getTaskId(),
                        task.getExecutorProvider(),
                        task.getSandboxProfile(),
                        optionalIdentifier(effective.modelRef(), "modelRef", 128),
                        defaultIdentifier(
                                effective.acceptanceProfile(),
                                "PROJECT_DEFAULT",
                                "acceptanceProfile",
                                128),
                        task.getObjective(),
                        effective.priority(),
                        effective.maxWallTimeSeconds(),
                        effective.approvalTimeoutSeconds()),
                DEFAULT_TENANT,
                trustedUserId);
        if (created == null || created.execution() == null) {
            throw new ControlManagedExecutionRuntimeClient.GatewayException(
                    502,
                    "MANAGED_RUNTIME_RESPONSE_INVALID",
                    "Runtime returned an invalid Managed Execution creation response");
        }
        projectionService.bindStarted(taskId, created.execution(), trustedUserId);
        return detail(taskId, trustedUserId);
    }

    public ManagedExecutionDetailView detail(String taskId, String actorUserId) {
        String trustedUserId = actor(actorUserId);
        AiCodingTaskEntity task = requireBoundTask(taskId);
        ExecutionView execution = runtimeClient.get(
                task.getManagedExecutionId(), DEFAULT_TENANT, trustedUserId);
        projectionService.projectSnapshot(taskId, execution);
        List<ArtifactView> artifacts = runtimeClient.artifacts(
                execution.executionId(), DEFAULT_TENANT, trustedUserId);
        ApprovalView approval = runtimeClient.approval(
                execution.executionId(), DEFAULT_TENANT, trustedUserId);
        return new ManagedExecutionDetailView(
                "reachai.ai-coding.managed-execution.v1",
                taskService.task(taskId),
                execution,
                artifacts,
                approval);
    }

    /** Lightweight status read used by the sanitized SSE progress stream. */
    public ExecutionView progress(String taskId, String actorUserId) {
        String trustedUserId = actor(actorUserId);
        AiCodingTaskEntity task = requireBoundTask(taskId);
        ExecutionView execution = runtimeClient.get(
                task.getManagedExecutionId(), DEFAULT_TENANT, trustedUserId);
        projectionService.projectSnapshot(taskId, execution);
        return execution;
    }

    public TaskView cancel(String taskId, String actorUserId) {
        String trustedUserId = actor(actorUserId);
        AiCodingTaskEntity task = requireManagedTask(taskId);
        if (!StringUtils.hasText(task.getManagedExecutionId())) {
            return taskService.cancel(taskId, trustedUserId);
        }
        ExecutionView execution = runtimeClient.cancel(
                task.getManagedExecutionId(),
                "Cancelled from the ReachAI AI Coding task console",
                DEFAULT_TENANT,
                trustedUserId);
        projectionService.projectSnapshot(taskId, execution);
        return taskService.task(taskId);
    }

    public ManagedExecutionDetailView resolveApproval(
            String taskId,
            String interactionId,
            ManagedApprovalDecisionCommand command,
            String actorUserId) {
        String trustedUserId = actor(actorUserId);
        AiCodingTaskEntity task = requireBoundTask(taskId);
        if (command == null || !StringUtils.hasText(command.decision())) {
            throw new IllegalArgumentException("approval decision is required");
        }
        String idempotencyKey = optionalIdentifier(
                command.idempotencyKey(), "idempotencyKey", 128);
        runtimeClient.resolveApproval(
                task.getManagedExecutionId(),
                identifier(interactionId, "interactionId", 64),
                new ApprovalDecisionRequest(command.decision(), idempotencyKey),
                DEFAULT_TENANT,
                trustedUserId);
        return detail(taskId, trustedUserId);
    }

    public List<ArtifactView> artifacts(String taskId, String actorUserId) {
        AiCodingTaskEntity task = requireBoundTask(taskId);
        return runtimeClient.artifacts(
                task.getManagedExecutionId(), DEFAULT_TENANT, actor(actorUserId));
    }

    public ArtifactContent artifact(
            String taskId,
            String artifactId,
            String actorUserId) {
        AiCodingTaskEntity task = requireBoundTask(taskId);
        return runtimeClient.artifact(
                task.getManagedExecutionId(),
                identifier(artifactId, "artifactId", 128),
                DEFAULT_TENANT,
                actor(actorUserId));
    }

    public boolean managed(String taskId) {
        AiCodingTaskEntity task = taskMapper.selectById(taskId);
        return task != null
                && ExecutionMode.MANAGED_SANDBOX.name().equals(task.getExecutionMode());
    }

    private AiCodingTaskEntity requireBoundTask(String taskId) {
        AiCodingTaskEntity task = requireManagedTask(taskId);
        if (!StringUtils.hasText(task.getManagedExecutionId())) {
            throw new IllegalStateException(
                    "Managed sandbox execution has not been started");
        }
        return task;
    }

    private AiCodingTaskEntity requireManagedTask(String taskId) {
        AiCodingTaskEntity task = taskMapper.selectById(
                identifier(taskId, "taskId", 40));
        if (task == null) throw new IllegalArgumentException("AI Coding task not found");
        if (!ExecutionMode.MANAGED_SANDBOX.name().equals(task.getExecutionMode())) {
            throw new IllegalStateException(
                    "AI Coding task does not use MANAGED_SANDBOX execution mode");
        }
        return task;
    }

    private String actor(String value) {
        return identifier(value, "actorUserId", 128);
    }

    private String defaultIdentifier(
            String value,
            String fallback,
            String field,
            int maximum) {
        return StringUtils.hasText(value)
                ? identifier(value, field, maximum).toUpperCase(Locale.ROOT)
                : fallback;
    }

    private String optionalIdentifier(String value, String field, int maximum) {
        return StringUtils.hasText(value) ? identifier(value, field, maximum) : null;
    }

    private String identifier(String value, String field, int maximum) {
        if (!StringUtils.hasText(value) || value.length() > maximum
                || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }

    public record ManagedExecutionStartCommand(
            String modelRef,
            String acceptanceProfile,
            Integer priority,
            Integer maxWallTimeSeconds,
            Integer approvalTimeoutSeconds) {
    }

    public record ManagedApprovalDecisionCommand(
            String decision,
            String idempotencyKey) {
    }

    public record ManagedExecutionDetailView(
            String schema,
            TaskView task,
            ExecutionView execution,
            List<ArtifactView> artifacts,
            ApprovalView approval) {
    }
}
