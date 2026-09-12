package com.enterprise.ai.control.aicoding.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.EventCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ActorType;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.QuestionStatus;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionMapper;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;

/** 在任务门面事务中处理客户端事件、人工取消及重放，复用统一状态机。 */
@Service
@RequiredArgsConstructor
public class AiCodingTaskEventService {
    private final AiCodingTaskStateChanges stateChanges;
    private final AiCodingTaskQuestionMapper questionMapper;
    private final AiCodingHandoffApplicationService handoffService;
    private final AiCodingSensitiveJsonSanitizer sanitizer;
    private final AiCodingTaskJsonSupport json;

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public AiCodingTaskEntity cancel(String taskId, String actor) {
        AiCodingTaskEntity task = stateChanges.requireTask(taskId);
        if (AiCodingTaskStateChanges.status(task) == ExecutionStatus.CANCELLED) {
            return task;
        }
        String normalizedActor = AiCodingTaskValues.optionalText(
                actor,
                "actor",
                AiCodingTaskValues.ACTOR_NAME_MAX_CHARACTERS);
        stateChanges.transition(
                task,
                ExecutionStatus.CANCELLED,
                null,
                "CANCELLED",
                "任务已取消",
                null,
                ActorType.USER,
                normalizedActor);
        handoffService.closeTaskHandoffs(task.getTaskId(), "TASK_CANCELLED");
        return task;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public AiCodingTaskEntity recordEvent(String taskId, EventCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("event request is required");
        }
        AiCodingTaskValues.requireSchema(command.schema(), AiCodingTaskValues.EVENT_SCHEMA, "event.schema");
        String clientEventId = AiCodingTaskValues.requiredText(
                command.clientEventId(),
                "clientEventId",
                AiCodingTaskValues.CLIENT_EVENT_ID_MAX_CHARACTERS);
        AiCodingTaskEntity task = stateChanges.requireTask(taskId);
        String eventType = AiCodingTaskValues.requiredText(command.eventType(), "eventType")
                .toUpperCase(Locale.ROOT);
        ActorType actorType = ActorType.AI_CODING;
        String actorName = AiCodingTaskValues.normalizeClientProvider(
                task.getExecutorProvider(),
                command.reportedBy(),
                "event.reportedBy");
        String eventMessage = AiCodingTaskValues.requiredText(
                sanitizer.sanitizeText(switch (eventType) {
                    case "STARTED" -> AiCodingTaskValues.firstText(
                            command.message(),
                            "AI Coding 客户端已开始");
                    case "PROGRESS", "FAILED" -> AiCodingTaskValues.requiredText(
                            command.message(),
                            "message");
                    case "RESUMED" -> AiCodingTaskValues.firstText(
                            command.message(),
                            "AI Coding 客户端已读取回答并继续");
                    default -> throw new IllegalArgumentException(
                            "unsupported eventType: " + eventType);
                }),
                "message",
                AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS);
        JsonNode safePayload = sanitizer.sanitize(command.payload());
        AiCodingTaskEventEntity duplicateEvent =
                stateChanges.findClientEvent(taskId, clientEventId);
        if (duplicateEvent != null) {
            requireSameEvent(
                    duplicateEvent,
                    eventType,
                    eventMessage,
                    safePayload,
                    actorName);
            return task;
        }
        ExecutionStatus current = AiCodingTaskStateChanges.status(task);

        switch (eventType) {
            case "STARTED" -> {
                if (current == ExecutionStatus.RUNNING) {
                    stateChanges.appendEvent(
                            task,
                            clientEventId,
                            eventType,
                            eventMessage,
                            safePayload,
                            actorType,
                            actorName);
                } else if (current == ExecutionStatus.READY) {
                    stateChanges.transition(
                            task,
                            ExecutionStatus.RUNNING,
                            clientEventId,
                            eventType,
                            eventMessage,
                            safePayload,
                            actorType,
                            actorName);
                } else {
                    throw new IllegalStateException(
                            "STARTED is only valid for READY or RUNNING tasks; "
                                    + "current status is " + current);
                }
            }
            case "PROGRESS" -> {
                if (current != ExecutionStatus.RUNNING
                        && current != ExecutionStatus.WAITING_USER) {
                    throw new IllegalStateException(
                            "PROGRESS is only valid for RUNNING or WAITING_USER tasks; "
                                    + "current status is " + current);
                }
                task.setLastMessage(eventMessage);
                task.setUpdatedAt(LocalDateTime.now());
                stateChanges.requireUpdated(task);
                stateChanges.appendEvent(
                        task,
                        clientEventId,
                        eventType,
                        eventMessage,
                        safePayload,
                        actorType,
                        actorName);
            }
            case "RESUMED" -> {
                stateChanges.requireStatus(task, ExecutionStatus.WAITING_USER);
                if (openQuestionCount(taskId) > 0) {
                    throw new IllegalStateException(
                            "task still has unanswered questions");
                }
                stateChanges.transition(
                        task,
                        ExecutionStatus.RUNNING,
                        clientEventId,
                        eventType,
                        eventMessage,
                        safePayload,
                        actorType,
                        actorName);
            }
            case "FAILED" -> stateChanges.transition(
                    task,
                    ExecutionStatus.FAILED,
                    clientEventId,
                    eventType,
                    eventMessage,
                    safePayload,
                    actorType,
                    actorName);
            default -> throw new IllegalStateException(
                    "validated eventType was not handled: " + eventType);
        }
        if (AiCodingTaskStateChanges.status(task).terminal()) {
            handoffService.closeTaskHandoffs(taskId, "TASK_" + task.getExecutionStatus());
        }
        return task;
    }

    private long openQuestionCount(String taskId) {
        return questionMapper.selectCount(
                Wrappers.<AiCodingTaskQuestionEntity>lambdaQuery()
                        .eq(AiCodingTaskQuestionEntity::getTaskId, taskId)
                        .eq(AiCodingTaskQuestionEntity::getStatus,
                                QuestionStatus.OPEN.name()));
    }

    private void requireSameEvent(
            AiCodingTaskEventEntity existing,
            String eventType,
            String message,
            JsonNode payload,
            String actorName) {
        JsonNode storedPayload = json.readJsonOrNull(existing.getPayloadJson());
        if (!eventType.equals(existing.getEventType())
                || !Objects.equals(message, existing.getMessage())
                || !json.sameJson(storedPayload, payload)
                || !Objects.equals(AiCodingTaskValues.optionalText(actorName), existing.getActorName())) {
            throw new IllegalStateException(
                    "clientEventId was already submitted with different content");
        }
    }

}
