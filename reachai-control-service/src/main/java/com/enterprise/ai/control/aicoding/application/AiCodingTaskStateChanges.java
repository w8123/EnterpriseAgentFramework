package com.enterprise.ai.control.aicoding.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskStateMachine;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ActorType;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.QuestionStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 在调用方事务中应用统一状态机、乐观锁和事件；终态同时关闭未回答问题。 */
@Component
@RequiredArgsConstructor
class AiCodingTaskStateChanges {
    private final AiCodingTaskMapper taskMapper;
    private final AiCodingTaskEventMapper eventMapper;
    private final AiCodingTaskQuestionMapper questionMapper;
    private final AiCodingTaskJsonSupport json;

    void requireStatus(
            AiCodingTaskEntity task,
            ExecutionStatus expected) {
        ExecutionStatus current = status(task);
        if (current != expected) {
            throw new IllegalStateException(
                    "task must be " + expected + " but is " + current);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void transition(
            AiCodingTaskEntity task,
            ExecutionStatus next,
            String clientEventId,
            String eventType,
            String message,
            JsonNode payload,
            ActorType actorType,
            String actorName) {
        ExecutionStatus current = status(task);
        AiCodingTaskStateMachine.requireTransition(current, next);
        String summary = AiCodingTaskValues.fitText(
                message,
                AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS);
        LocalDateTime now = LocalDateTime.now();
        task.setExecutionStatus(next.name());
        task.setLastMessage(summary);
        task.setUpdatedAt(now);
        if (next == ExecutionStatus.RUNNING && task.getStartedAt() == null) {
            task.setStartedAt(now);
        }
        if (next == ExecutionStatus.RESULT_SUBMITTED) {
            task.setResultSubmittedAt(now);
        }
        if (next.terminal()) {
            task.setCompletedAt(now);
            closeOpenQuestions(task.getTaskId(), now);
        }
        requireUpdated(task);
        appendEvent(
                task,
                clientEventId,
                eventType,
                summary,
                payload,
                actorType,
                actorName);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void appendEvent(
            AiCodingTaskEntity task,
            String clientEventId,
            String eventType,
            String message,
            JsonNode payload,
            ActorType actorType,
            String actorName) {
        AiCodingTaskEventEntity event = new AiCodingTaskEventEntity();
        event.setTaskId(task.getTaskId());
        event.setClientEventId(AiCodingTaskValues.optionalText(
                clientEventId,
                "clientEventId",
                AiCodingTaskValues.CLIENT_EVENT_ID_MAX_CHARACTERS));
        event.setEventType(AiCodingTaskValues.requiredText(
                eventType,
                "eventType",
                AiCodingTaskValues.EVENT_TYPE_MAX_CHARACTERS));
        event.setExecutionStatusAfter(task.getExecutionStatus());
        event.setMessage(AiCodingTaskValues.fitText(
                AiCodingTaskValues.optionalText(message),
                AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS));
        event.setPayloadJson(payload == null ? null : json.writeJson(payload));
        event.setActorType(actorType.name());
        event.setActorName(AiCodingTaskValues.optionalText(
                actorName,
                "actorName",
                AiCodingTaskValues.ACTOR_NAME_MAX_CHARACTERS));
        event.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(event);
    }

    void closeOpenQuestions(String taskId, LocalDateTime now) {
        questionMapper.update(
                null,
                Wrappers.<AiCodingTaskQuestionEntity>lambdaUpdate()
                        .eq(AiCodingTaskQuestionEntity::getTaskId, taskId)
                        .eq(AiCodingTaskQuestionEntity::getStatus,
                                QuestionStatus.OPEN.name())
                        .set(AiCodingTaskQuestionEntity::getStatus,
                                QuestionStatus.CLOSED.name())
                        .set(AiCodingTaskQuestionEntity::getUpdatedAt, now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void requireUpdated(AiCodingTaskEntity task) {
        int updated = taskMapper.updateById(task);
        if (updated != 1) {
            throw new IllegalStateException(
                    "AI Coding task was concurrently modified; reload and retry");
        }
    }

    AiCodingTaskEntity requireTask(String taskId) {
        AiCodingTaskEntity task = taskMapper.selectById(AiCodingTaskValues.requiredText(taskId, "taskId"));
        if (task == null) {
            throw new IllegalArgumentException(
                    "AI Coding task not found: " + taskId);
        }
        return task;
    }

    AiCodingTaskEventEntity findClientEvent(
            String taskId,
            String clientEventId) {
        return eventMapper.selectOne(
                Wrappers.<AiCodingTaskEventEntity>lambdaQuery()
                        .eq(AiCodingTaskEventEntity::getTaskId, taskId)
                        .eq(AiCodingTaskEventEntity::getClientEventId, clientEventId)
                        .last("LIMIT 1"));
    }

    static ExecutionStatus status(AiCodingTaskEntity task) {
        return ExecutionStatus.valueOf(task.getExecutionStatus());
    }
}
