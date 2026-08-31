package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionMode;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.managed.ControlManagedExecutionInboxProjector;
import com.enterprise.ai.control.managed.ControlManagedExecutionInboxProjector.ProjectionEvent;
import com.enterprise.ai.control.managed.ControlManagedExecutionInboxProjector.ProjectionResult;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.ExecutionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Locale;

/** Projects Runtime-owned execution facts into the AI Coding task kernel. */
@Service
@RequiredArgsConstructor
public class AiCodingManagedExecutionProjectionService
        implements ControlManagedExecutionInboxProjector {

    private static final String EVENT_SCHEMA =
            "reachai.ai-coding.managed-execution-event.v1";

    private final AiCodingTaskMapper taskMapper;
    private final AiCodingTaskEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public AiCodingTaskEntity bindStarted(
            String taskId,
            ExecutionView execution,
            String actorUserId) {
        if (execution == null || !StringUtils.hasText(execution.executionId())) {
            throw new IllegalArgumentException("managed execution is required");
        }
        AiCodingTaskEntity task = requireTask(taskId);
        requireManagedTask(task);
        requireRuntimeIdentity(task, execution.executionId(), execution.projectCode(),
                execution.sourceType(), execution.sourceRef());
        if (StringUtils.hasText(task.getManagedExecutionId())
                && !execution.executionId().equals(task.getManagedExecutionId())) {
            throw new IllegalStateException(
                    "AI Coding task is already bound to a different Managed Execution");
        }
        LocalDateTime now = LocalDateTime.now();
        String message = message(execution.status());
        if (taskMapper.bindManagedExecution(
                taskId,
                execution.executionId(),
                execution.status(),
                execution.pendingInteractionId(),
                message,
                now) != 1) {
            AiCodingTaskEntity concurrent = requireTask(taskId);
            if (!execution.executionId().equals(concurrent.getManagedExecutionId())) {
                throw new IllegalStateException(
                        "AI Coding task changed while Managed Execution was started");
            }
            task = concurrent;
        } else {
            task = requireTask(taskId);
        }
        ObjectNode payload = snapshotPayload(execution);
        if (StringUtils.hasText(actorUserId)) payload.put("startedByUserId", actorUserId);
        eventMapper.insertManagedEvent(
                taskId,
                "managed-start:" + execution.executionId(),
                "MANAGED_EXECUTION_STARTED",
                task.getExecutionStatus(),
                message,
                write(payload),
                now);
        return task;
    }

    @Transactional
    public ProjectionResult projectSnapshot(String taskId, ExecutionView execution) {
        if (execution == null) {
            return ProjectionResult.failed("Runtime execution snapshot is missing");
        }
        String clientEventId = fitClientEventId(
                "managed-snapshot:" + execution.executionId()
                        + ":" + execution.lastEventSequence()
                        + ":" + execution.status()
                        + ":" + execution.approvalCount());
        return project(
                taskId,
                execution.executionId(),
                execution.projectCode(),
                execution.sourceType(),
                execution.sourceRef(),
                execution.status(),
                execution.pendingInteractionId(),
                clientEventId,
                "MANAGED_EXECUTION_SNAPSHOT",
                snapshotPayload(execution));
    }

    @Override
    public boolean supports(ProjectionEvent event) {
        return event != null && "AI_CODING_TASK".equals(event.sourceType());
    }

    @Override
    @Transactional
    public ProjectionResult project(ProjectionEvent inbox) {
        if (!supports(inbox)) {
            return ProjectionResult.ignored();
        }
        if (!StringUtils.hasText(inbox.sourceRef())) {
            return ProjectionResult.failed("AI Coding Managed Execution sourceRef is missing");
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(inbox.payloadJson());
        } catch (Exception invalid) {
            return ProjectionResult.failed("Managed Execution inbox payload is invalid");
        }
        String interactionId = text(payload, "interactionId");
        return project(
                inbox.sourceRef(),
                inbox.executionId(),
                inbox.projectCode(),
                inbox.sourceType(),
                inbox.sourceRef(),
                inbox.runtimeStatus(),
                interactionId,
                inbox.eventId(),
                inbox.eventType(),
                payload);
    }

    private ProjectionResult project(
            String taskId,
            String executionId,
            String projectCode,
            String sourceType,
            String sourceRef,
            String runtimeStatus,
            String interactionId,
            String clientEventId,
            String eventType,
            JsonNode payload) {
        for (int attempt = 0; attempt < 3; attempt++) {
            AiCodingTaskEntity task = taskMapper.selectById(taskId);
            if (task == null) {
                return ProjectionResult.pending("AI Coding task binding is not visible yet");
            }
            if (!isManagedTask(task)) {
                return ProjectionResult.failed(
                        "Runtime event targets a non-managed AI Coding task");
            }
            if (!StringUtils.hasText(task.getManagedExecutionId())) {
                return ProjectionResult.pending("Managed Execution binding is not visible yet");
            }
            try {
                requireRuntimeIdentity(task, executionId, projectCode, sourceType, sourceRef);
            } catch (RuntimeException mismatch) {
                return ProjectionResult.failed(mismatch.getMessage());
            }
            String normalizedRuntimeStatus = normalizedRuntimeStatus(runtimeStatus);
            ExecutionStatus nextTaskStatus = projectedTaskStatus(
                    status(task), normalizedRuntimeStatus);
            String projectedInteraction = "WAITING_APPROVAL".equals(normalizedRuntimeStatus)
                    ? firstText(interactionId, task.getManagedPendingInteractionId())
                    : null;
            LocalDateTime now = LocalDateTime.now();
            String projectedMessage = message(normalizedRuntimeStatus);
            int updated = taskMapper.projectManagedExecution(
                    task.getTaskId(),
                    executionId,
                    normalizedRuntimeStatus,
                    projectedInteraction,
                    nextTaskStatus.name(),
                    projectedMessage,
                    task.getLockVersion(),
                    now);
            if (updated != 1) continue;
            eventMapper.insertManagedEvent(
                    task.getTaskId(),
                    fitClientEventId(clientEventId),
                    normalizeEventType(eventType),
                    nextTaskStatus.name(),
                    projectedMessage,
                    write(eventPayload(executionId, normalizedRuntimeStatus, payload)),
                    now);
            return ProjectionResult.applied();
        }
        return ProjectionResult.pending(
                "AI Coding task changed concurrently; projection will retry");
    }

    private void requireRuntimeIdentity(
            AiCodingTaskEntity task,
            String executionId,
            String projectCode,
            String sourceType,
            String sourceRef) {
        if (!task.getTaskId().equals(sourceRef)
                || !"AI_CODING_TASK".equals(sourceType)
                || !task.getProjectCode().equalsIgnoreCase(projectCode)
                || (StringUtils.hasText(task.getManagedExecutionId())
                    && !task.getManagedExecutionId().equals(executionId))) {
            throw new IllegalStateException(
                    "Managed Execution identity does not match the AI Coding task");
        }
    }

    private ExecutionStatus projectedTaskStatus(
            ExecutionStatus current,
            String runtimeStatus) {
        if (current.terminal()
                || current == ExecutionStatus.RESULT_APPLIED
                || current == ExecutionStatus.ACCEPTANCE_READY) {
            return current;
        }
        if ("SUCCEEDED".equals(runtimeStatus)) {
            return current == ExecutionStatus.RESULT_SUBMITTED
                    ? current
                    : ExecutionStatus.RESULT_SUBMITTED;
        }
        if ("FAILED".equals(runtimeStatus) || "TIMED_OUT".equals(runtimeStatus)) {
            return current == ExecutionStatus.RESULT_SUBMITTED
                    ? current
                    : ExecutionStatus.FAILED;
        }
        if ("CANCELLED".equals(runtimeStatus)) {
            return current == ExecutionStatus.RESULT_SUBMITTED
                    ? current
                    : ExecutionStatus.CANCELLED;
        }
        if ("WAITING_APPROVAL".equals(runtimeStatus)
                || "WAITING_USER".equals(runtimeStatus)) {
            return current == ExecutionStatus.RESULT_SUBMITTED
                    ? current
                    : ExecutionStatus.WAITING_USER;
        }
        return current == ExecutionStatus.RESULT_SUBMITTED
                ? current
                : ExecutionStatus.RUNNING;
    }

    private ObjectNode snapshotPayload(ExecutionView execution) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema", EVENT_SCHEMA);
        payload.put("executionId", execution.executionId());
        payload.put("status", execution.status());
        payload.put("cleanupStatus", execution.cleanupStatus());
        payload.put("sandboxProfile", execution.sandboxProfile());
        payload.put("lastEventSequence", execution.lastEventSequence());
        payload.put("approvalCount", execution.approvalCount());
        if (StringUtils.hasText(execution.pendingInteractionId())) {
            payload.put("interactionId", execution.pendingInteractionId());
        }
        if (StringUtils.hasText(execution.errorCode())) {
            payload.put("errorCode", execution.errorCode());
        }
        return payload;
    }

    private ObjectNode eventPayload(
            String executionId,
            String runtimeStatus,
            JsonNode source) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema", EVENT_SCHEMA);
        payload.put("executionId", executionId);
        payload.put("status", runtimeStatus);
        if (source != null && source.isObject()) {
            copyText(source, payload, "interactionId");
            copyText(source, payload, "approvalRequestId");
            copyText(source, payload, "approvalKind");
            copyText(source, payload, "decision");
            if (source.path("sequence").canConvertToInt()) {
                payload.put("sequence", source.path("sequence").intValue());
            }
        }
        return payload;
    }

    private void copyText(JsonNode source, ObjectNode target, String field) {
        String value = text(source, field);
        if (StringUtils.hasText(value) && value.length() <= 160) {
            target.put(field, value);
        }
    }

    private String normalizedRuntimeStatus(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Managed Execution status is required");
        }
        String status = value.trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "REQUESTED", "QUEUED", "PROVISIONING", "RUNNING",
                    "WAITING_APPROVAL", "WAITING_USER", "FINALIZING",
                    "CANCELLING", "SUCCEEDED", "FAILED", "TIMED_OUT",
                    "CANCELLED" -> status;
            default -> throw new IllegalArgumentException(
                    "Managed Execution status is invalid");
        };
    }

    private String message(String runtimeStatus) {
        return switch (normalizedRuntimeStatus(runtimeStatus)) {
            case "REQUESTED", "QUEUED" -> "隔离执行已排队";
            case "PROVISIONING" -> "正在创建隔离工作区";
            case "RUNNING" -> "Codex 正在隔离工作区执行";
            case "WAITING_APPROVAL" -> "隔离执行等待一次性操作审批";
            case "WAITING_USER" -> "隔离执行等待用户输入";
            case "FINALIZING" -> "正在独立校验证据包";
            case "CANCELLING" -> "正在取消隔离执行";
            case "SUCCEEDED" -> "隔离执行已完成，证据包已通过 Runtime 校验";
            case "FAILED" -> "隔离执行失败";
            case "TIMED_OUT" -> "隔离执行超时";
            case "CANCELLED" -> "隔离执行已取消";
            default -> "隔离执行状态已更新";
        };
    }

    private String normalizeEventType(String value) {
        if (!StringUtils.hasText(value)) return "MANAGED_EXECUTION";
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_]", "_");
        return normalized.length() <= 40
                ? normalized
                : "MANAGED_EXECUTION";
    }

    private String fitClientEventId(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim();
        return normalized.length() <= 96
                ? normalized
                : normalized.substring(0, 96);
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception impossible) {
            throw new IllegalStateException(
                    "Managed Execution projection serialization failed", impossible);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private ExecutionStatus status(AiCodingTaskEntity task) {
        return ExecutionStatus.valueOf(task.getExecutionStatus());
    }

    private boolean isManagedTask(AiCodingTaskEntity task) {
        return ExecutionMode.MANAGED_SANDBOX.name().equals(task.getExecutionMode());
    }

    private void requireManagedTask(AiCodingTaskEntity task) {
        if (!isManagedTask(task)) {
            throw new IllegalStateException(
                    "AI Coding task does not use MANAGED_SANDBOX execution mode");
        }
    }

    private AiCodingTaskEntity requireTask(String taskId) {
        AiCodingTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) throw new IllegalArgumentException("AI Coding task not found");
        return task;
    }

}
