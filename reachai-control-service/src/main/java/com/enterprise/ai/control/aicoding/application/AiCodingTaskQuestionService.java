package com.enterprise.ai.control.aicoding.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AnswerQuestionCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AskQuestionCommand;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns question replay, answering and conditional writes inside the facade transaction. */
@Service
@RequiredArgsConstructor
public class AiCodingTaskQuestionService {
    private final AiCodingTaskStateChanges stateChanges;
    private final AiCodingTaskQuestionMapper questionMapper;
    private final AiCodingSensitiveJsonSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final AiCodingTaskJsonSupport json;

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public AiCodingTaskQuestionEntity askQuestion(String taskId, AskQuestionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("question request is required");
        }
        AiCodingTaskValues.requireSchema(
                command.schema(),
                AiCodingTaskValues.QUESTION_SCHEMA,
                "question.schema");
        String clientEventId = AiCodingTaskValues.requiredText(
                command.clientEventId(),
                "clientEventId",
                AiCodingTaskValues.CLIENT_EVENT_ID_MAX_CHARACTERS);
        String questionId = AiCodingTaskValues.requiredText(
                command.questionId(),
                "questionId",
                AiCodingTaskValues.QUESTION_ID_MAX_CHARACTERS);
        AiCodingTaskEntity task = stateChanges.requireTask(taskId);
        String title = AiCodingTaskValues.requiredText(
                sanitizer.sanitizeText(AiCodingTaskValues.requiredText(command.title(), "title")),
                "title",
                AiCodingTaskValues.QUESTION_TITLE_MAX_CHARACTERS);
        String body = AiCodingTaskValues.requiredTextUtf8(
                sanitizer.sanitizeText(AiCodingTaskValues.requiredText(command.body(), "body")),
                "body",
                AiCodingTaskValues.TEXT_MAX_UTF8_BYTES);
        List<String> options = normalizeOptions(command.options()).stream()
                .map(sanitizer::sanitizeText)
                .toList();
        String optionsJson = AiCodingTaskValues.requireMaxUtf8Bytes(
                json.writeJson(options),
                "options",
                AiCodingTaskValues.TEXT_MAX_UTF8_BYTES);
        String askedBy = AiCodingTaskValues.normalizeClientProvider(
                task.getExecutorProvider(),
                command.askedBy(),
                "question.askedBy");
        AiCodingTaskQuestionEntity existing = questionMapper.selectById(questionId);
        if (existing != null) {
            if (!existing.getTaskId().equals(taskId)) {
                throw new IllegalStateException(
                        "questionId is already used by another task");
            }
            requireSameQuestion(
                    existing,
                    title,
                    body,
                    optionsJson,
                    askedBy);
            AiCodingTaskEventEntity duplicateEvent =
                    stateChanges.findClientEvent(taskId, clientEventId);
            if (duplicateEvent != null) {
                JsonNode payload = json.readJsonOrNull(
                        duplicateEvent.getPayloadJson());
                if (!"QUESTION".equals(duplicateEvent.getEventType())
                        || payload == null
                        || !questionId.equals(
                                text(payload.get("questionId")))) {
                    throw new IllegalStateException(
                            "clientEventId is already used by a different request");
                }
            }
            return existing;
        }
        if (stateChanges.findClientEvent(taskId, clientEventId) != null) {
            throw new IllegalStateException(
                    "clientEventId is already used without this questionId");
        }

        stateChanges.requireStatus(task, ExecutionStatus.RUNNING);
        LocalDateTime now = LocalDateTime.now();
        AiCodingTaskQuestionEntity question = new AiCodingTaskQuestionEntity();
        question.setQuestionId(questionId);
        question.setTaskId(taskId);
        question.setTitle(title);
        question.setBody(body);
        question.setOptionsJson(optionsJson);
        question.setStatus(QuestionStatus.OPEN.name());
        question.setAskedBy(askedBy);
        question.setAskedAt(now);
        question.setUpdatedAt(now);
        questionMapper.insert(question);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", questionId);
        stateChanges.transition(
                task,
                ExecutionStatus.WAITING_USER,
                clientEventId,
                "QUESTION",
                "AI Coding 客户端等待用户回答：" + question.getTitle(),
                payload,
                ActorType.AI_CODING,
                question.getAskedBy());
        return question;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public AiCodingTaskQuestionEntity answerQuestion(
            String taskId,
            String questionId,
            AnswerQuestionCommand command) {
        AiCodingTaskEntity task = stateChanges.requireTask(taskId);
        AiCodingTaskQuestionEntity question = questionMapper.selectById(
                AiCodingTaskValues.requiredText(questionId, "questionId"));
        if (question == null || !taskId.equals(question.getTaskId())) {
            throw new IllegalArgumentException(
                    "AI Coding question not found: " + questionId);
        }
        String answer = AiCodingTaskValues.requiredTextUtf8(
                sanitizer.sanitizeText(AiCodingTaskValues.requiredText(
                        command == null ? null : command.answer(),
                        "answer")),
                "answer",
                AiCodingTaskValues.TEXT_MAX_UTF8_BYTES);
        String answeredBy = AiCodingTaskValues.optionalText(
                command.answeredBy(),
                "answeredBy",
                AiCodingTaskValues.ACTOR_NAME_MAX_CHARACTERS);
        if (QuestionStatus.ANSWERED.name().equals(question.getStatus())) {
            if (!Objects.equals(question.getAnswer(), answer)) {
                throw new IllegalStateException(
                        "question was already answered with different content");
            }
            return question;
        }
        if (!QuestionStatus.OPEN.name().equals(question.getStatus())) {
            throw new IllegalStateException("question is not open");
        }
        stateChanges.requireStatus(task, ExecutionStatus.WAITING_USER);
        LocalDateTime now = LocalDateTime.now();
        int answered = questionMapper.update(
                null,
                Wrappers.<AiCodingTaskQuestionEntity>update()
                        .eq("question_id", questionId)
                        .eq("task_id", taskId)
                        .eq("status", QuestionStatus.OPEN.name())
                        .set("status", QuestionStatus.ANSWERED.name())
                        .set("answer", answer)
                        .set("answered_by", answeredBy)
                        .set("answered_at", now)
                        .set("updated_at", now));
        if (answered != 1) {
            AiCodingTaskQuestionEntity raced =
                    questionMapper.selectById(questionId);
            if (raced != null
                    && taskId.equals(raced.getTaskId())
                    && QuestionStatus.ANSWERED.name().equals(raced.getStatus())) {
                if (!Objects.equals(raced.getAnswer(), answer)) {
                    throw new IllegalStateException(
                            "question was already answered with different content");
                }
                return raced;
            }
            throw new IllegalStateException("question is not open");
        }
        question.setStatus(QuestionStatus.ANSWERED.name());
        question.setAnswer(answer);
        question.setAnsweredBy(answeredBy);
        question.setAnsweredAt(now);
        question.setUpdatedAt(now);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", questionId);
        stateChanges.appendEvent(
                task,
                null,
                "QUESTION_ANSWERED",
                "用户已回答，等待 " + task.getExecutorProvider() + " 读取并继续",
                payload,
                ActorType.USER,
                answeredBy);
        task.setLastMessage(
                "用户已回答问题，等待 " + task.getExecutorProvider() + " 继续");
        task.setUpdatedAt(now);
        stateChanges.requireUpdated(task);
        return question;
    }

    private void requireSameQuestion(
            AiCodingTaskQuestionEntity existing,
            String title,
            String body,
            String optionsJson,
            String askedBy) {
        if (!title.equals(existing.getTitle())
                || !body.equals(existing.getBody())
                || !json.sameJson(
                        json.readJsonOrNull(existing.getOptionsJson()),
                        json.readJsonOrNull(optionsJson))
                || !Objects.equals(AiCodingTaskValues.optionalText(askedBy), existing.getAskedBy())) {
            throw new IllegalStateException(
                    "questionId was already submitted with different content");
        }
    }

    private static List<String> normalizeOptions(List<String> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        if (options.size() > AiCodingTaskValues.QUESTION_OPTION_MAX_COUNT) {
            throw new IllegalArgumentException(
                    "options must not contain more than "
                            + AiCodingTaskValues.QUESTION_OPTION_MAX_COUNT
                            + " items");
        }
        List<String> normalized = new ArrayList<>(options.size());
        for (String option : options) {
            normalized.add(AiCodingTaskValues.requiredText(
                    option,
                    "options[]",
                    AiCodingTaskValues.QUESTION_OPTION_MAX_CHARACTERS));
        }
        return List.copyOf(normalized);
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

}
